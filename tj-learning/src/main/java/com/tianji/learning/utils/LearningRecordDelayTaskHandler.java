package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * <h2>学习记录延迟任务处理器（线程池模式）</h2>
 *
 * <p><b>业务背景：</b></p>
 * <p>用户观看视频时，前端每隔一定时间会上报播放进度（moment）。如果每次上报都直接写数据库，
 * 会对数据库造成较大压力。因此引入延迟队列 + Redis 缓存的机制：</p>
 * <ol>
 *   <li>用户上报播放进度 → 先写入 Redis 缓存，同时向延迟队列投递一个延迟任务（延迟20秒）。</li>
 *   <li>20秒后，延迟任务被消费：从 Redis 中取出当前缓存的 moment，与任务中的 moment 比较。</li>
 *   <li>如果 moment 一致 → 说明用户在这20秒内没有新的上报，即用户已停止观看，此时将数据持久化到数据库。</li>
 *   <li>如果 moment 不一致 → 说明用户还在继续观看，丢弃本次旧数据，不做数据库写入。</li>
 * </ol>
 *
 * <p><b>架构说明（线程池改造）：</b></p>
 * <p>该类原本使用单线程模式消费延迟队列（一条线程死循环调用 {@code queue.take()}）。
 * 现改造为<b>线程池模式</b>：启动 N 条消费者线程（N = CPU 核心数），共同竞争同一个
 * {@link DelayQueue}，实现多线程并行处理延迟任务。</p>
 *
 * <p><b>为什么这个改造是安全的？</b></p>
 * <ul>
 *   <li>{@link DelayQueue} 内部使用 {@link java.util.concurrent.locks.ReentrantLock} 保证线程安全，
 *       {@code take()/poll()/add()} 操作都是原子的，一条任务只会被一条线程取走。</li>
 *   <li>每条任务的处理逻辑是独立的（读 Redis → 比较 moment → 写 DB），任务之间没有共享可变状态。</li>
 *   <li>即使同一 (lessonId, sectionId) 被两次投递（用户在短时间内触发两次上报），
 *       两条任务被不同线程处理，最多导致一次多余的数据库 UPDATE，而 UPDATE 本身是幂等的。</li>
 * </ul>
 *
 * <p><b>优雅关闭机制：</b></p>
 * <ol>
 *   <li>{@link #destroy()} 被 Spring 容器调用时，先设置 {@link #running} 为 false，通知所有消费者线程停止循环。</li>
 *   <li>调用 {@code executorService.shutdown()}，线程池不再接受新任务。</li>
 *   <li>等待最多30秒让正在处理的任务完成（{@code awaitTermination}）。</li>
 *   <li>超时未完成则强制中断（{@code shutdownNow}）。</li>
 * </ol>
 *
 * <p><b>关键设计抉择：queue.take() → queue.poll(timeout)</b></p>
 * <p>{@code take()} 会无限期阻塞直到有任务可用，即使 {@code running} 被设为 false，
 * 线程也无法从阻塞中醒来。改用 {@code poll(1, TimeUnit.SECONDS)} 后，
 * 线程最多阻塞1秒就会返回 null，然后回到 while 循环检查 {@code running} 标志位，
 * 从而实现对停止信号的及时响应（最多延迟1秒）。</p>
 *
 * <p><b>线程数选择：CPU 核心数</b></p>
 * <p>每条任务的主要操作是 Redis 网络 IO + 数据库网络 IO，属于 IO 密集型任务。
 * 以 CPU 核心数作为线程数是合理的起点：
 * <ul>
 *   <li>线程太少 → 任务吞吐量受限，延迟队列可能积压。</li>
 *   <li>线程太多 → 数据库连接池和 Redis 连接池可能成为瓶颈，上下文切换开销增大。</li>
 *   <li>后续可根据线上监控指标（队列积压量、DB 连接使用率）进行调优。</li>
 * </ul>
 *
 * @author QinZhennan
 * @see DelayQueue
 * @see DelayTask
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper;
    private final ILearningLessonService lessonService;

    /**
     * 延迟队列，存储所有待处理的延迟任务。
     * {@link DelayQueue} 自身是线程安全的，多个线程可以同时 add/take/poll 而不需要额外同步。
     */
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();

    /**
     * Redis 缓存的 key 模板，格式为 {@code learning:record:{lessonId}}，
     * 使用 Hash 结构存储，field 为 sectionId，value 为播放进度的 JSON 字符串。
     */
    private final static String RECORD_KEY_TEMPLATE = "learning:record:{}";

    // ==================== 线程池消费相关配置 ====================

    /**
     * 消费者线程的运行标志位。
     * 使用 {@link AtomicBoolean} 而非 {@code volatile boolean}，语义更明确，
     * 且在多线程环境下对 flag 的读写具有可见性保证。
     * 当 Spring 容器销毁 Bean 时，{@link #destroy()} 将其设为 false，
     * 所有消费者线程在下一轮 poll 超时后检查到此标志并退出循环。
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 管理消费者线程的线程池。
     * 注意：这里不是每来一个任务就 submit 一次，而是在初始化时提交 N 个长期存活的消费者线程。
     * 因此线程池的队列和拒绝策略几乎用不到，核心作用是统一管理线程的生命周期。
     */
    private ExecutorService executorService;

    /**
     * 消费者线程的数量，等于 CPU 核心数（逻辑核心）。
     * 通过 {@link Runtime#getRuntime()#availableProcessors()} 在类加载时获取，
     * 对于大多数现代 CPU（支持超线程），这个值通常是物理核心数 × 2。
     */
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 从延迟队列中 poll 任务的超时时间（秒）。
     * 设为1秒是经过权衡的：
     * <ul>
     *   <li>不能太长：否则 destroy() 发出停止信号后，线程要等很久才能响应。</li>
     *   <li>不能太短：否则会产生大量空轮询，浪费 CPU（虽然 poll 阻塞期间不消耗 CPU，但频繁唤醒也有开销）。</li>
     *   <li>1秒对于业务延迟精度（20秒级别）来说完全可接受。</li>
     * </ul>
     */
    private static final long POLL_TIMEOUT_SECONDS = 1;

    /**
     * destroy() 中等待线程池终止的最大时间（秒）。
     * 正常情况下，poll 超时最多1秒 + 当前正在处理的任务的 DB 操作时间（通常 < 1秒），
     * 所以30秒已经非常充裕。如果超时，说明有线程卡死，需要强制中断。
     */
    private static final long SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * <h3>初始化方法</h3>
     * <p>由 Spring 容器在 Bean 构造完成后调用（{@link PostConstruct}）。</p>
     * <p>主要工作：</p>
     * <ol>
     *   <li>创建固定大小的线程池，核心线程数 = CPU 核心数。</li>
     *   <li>为每个线程起有意义的名字（{@code learning-delay-1, learning-delay-2, ...}），
     *       方便排查线上问题时在 jstack 日志中定位。</li>
     *   <li>提交 N 个消费者任务到线程池，每个任务运行 {@link #handleDelayTask()}。。
     *       注意：这里设置 {@code setDaemon(false)}，确保消费者线程是用户线程，
     *       JVM 不会在还有未处理任务时意外退出。</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        // 用于给线程编号，生成有意义的名字
        final AtomicInteger threadIndex = new AtomicInteger(0);

        // 创建固定大小的线程池。
        // 选择 newFixedThreadPool 而非 ThreadPoolExecutor 直接构造，因为：
        // - 我们的消费者线程是长期存活的，不会动态扩缩容
        // - 不需要向线程池队列提交任务（任务是通过 DelayQueue 分发的）
        // - 代码更简洁，可读性更好
        executorService = Executors.newFixedThreadPool(THREAD_COUNT, r -> {
            Thread t = new Thread(r, "learning-delay-" + threadIndex.incrementAndGet());
            // 必须设置为非守护线程：如果是守护线程，当主线程结束时 JVM 会直接退出，
            // 不管延迟队列中是否还有未持久化的数据，可能导致用户播放进度丢失。
            t.setDaemon(false);
            return t;
        });

        // 启动 THREAD_COUNT 条消费者线程，它们共同竞争同一个 DelayQueue。
        // 每条线程执行相同的 handleDelayTask() 逻辑：
        //   while(running) → poll(1秒超时) → 处理任务
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(this::handleDelayTask);
        }

        log.info("延迟任务处理器启动完成, 消费者线程数={}, poll超时={}秒, 关闭等待={}秒",
                THREAD_COUNT, POLL_TIMEOUT_SECONDS, SHUTDOWN_AWAIT_SECONDS);
    }

    /**
     * <h3>销毁方法</h3>
     * <p>由 Spring 容器在 Bean 销毁前调用（{@link PreDestroy}），实现优雅关闭。</p>
     *
     * <p><b>关闭流程（分三步走）：</b></p>
     * <ol>
     *   <li><b>设置停止标志位</b>：{@code running.set(false)}，
     *       所有正在 poll 等待的消费者线程在超时（最多1秒）后检查到此标志，退出 while 循环。</li>
     *   <li><b>关闭线程池</b>：{@code executorService.shutdown()}，
     *       线程池不再接受新的 submit，但会等待所有已提交的线程自然执行完毕。
     *       这里"已提交的线程"就是那 N 条消费者线程，它们在步骤1中已被通知退出。</li>
     *   <li><b>等待终止</b>：{@code awaitTermination(30, SECONDS)}，
     *       等待最多30秒。正常情况下1~2秒内所有线程就会退出。
     *       如果超时，说明有线程卡在不可中断的 IO 或死锁中，调用 {@code shutdownNow()} 强制中断。</li>
     * </ol>
     *
     * <p><b>为什么先设标志位再 shutdown？</b></p>
     * <p>如果反过来操作（先 shutdown 再设标志位），线程池的 shutdown 不会中断正在 poll 的线程，
     * 线程会一直阻塞直到 poll 返回 null（1秒后）。虽然最终会退出，但先设标志位让线程尽早
     * 感知到停止意图，语义更清晰。</p>
     *
     * <p><b>中断恢复</b>：{@code Thread.currentThread().interrupt()} 保持中断状态。</p>
     */
    @PreDestroy
    public void destroy() {
        log.info("正在停止延迟任务处理器, 队列中剩余任务数={}", queue.size());

        // 步骤1：通知所有消费者线程停止循环。
        // 原子操作，对所有线程立即可见（AtomicBoolean 保证内存可见性）。
        running.set(false);

        // 步骤2：线程池进入"软关闭"状态，不再接受新任务，但已有任务继续运行。
        executorService.shutdown();

        try {
            // 步骤3：等待线程池完全终止，最多等30秒。
            boolean terminated = executorService.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
            if (terminated) {
                log.info("延迟任务处理器正常关闭完成, 队列中剩余任务数={}", queue.size());
            } else {
                // 超时未终止，说明有线程卡死。
                // shutdownNow() 会中断所有正在运行的线程，线程中的 poll()/IO 操作会收到 InterruptedException，
                // 然后在 catch 块中 break 退出。未处理的任务仍在 DelayQueue 中，但队列本身在内存中，
                // 进程停止后这些任务会丢失。好在 Redis 中还有缓存数据（30秒过期），重启后会重新建立。
                log.warn("延迟任务处理器等待{}秒后仍未关闭，执行强制终止", SHUTDOWN_AWAIT_SECONDS);
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 当前线程（Spring 容器线程）在 awaitTermination 期间被中断。
            log.error("等待关闭时被中断", e);
            // 立即执行强制终止，保证 JVM 能正常退出。
            executorService.shutdownNow();
            // 恢复中断状态，让上层调用者（Spring 容器）知道当前线程被中断过。
            Thread.currentThread().interrupt();
        }
    }

    /**
     * <h3>延迟任务消费循环（每条消费者线程的入口方法）</h3>
     *
     * <p><b>执行流程：</b></p>
     * <ol>
     *   <li>通过 {@code queue.poll(1, SECONDS)} 以超时方式获取任务，而非 {@code queue.take()}。
     *       这是本改造的关键——超时后线程有机会检查 {@code running} 标志位，实现优雅关闭。</li>
     *   <li>从 Redis 中读取对应 (lessonId, sectionId) 的缓存播放进度。</li>
     *   <li>比较任务中的 moment 与 Redis 缓存中的 moment：
     *     <ul>
     *       <li>一致 → 用户已停止观看，将播放进度持久化到数据库。</li>
     *       <li>不一致 → 用户还在继续观看（产生了新的上报），丢弃当前任务。</li>
     *     </ul>
     *   </li>
     *   <li>持久化操作：更新 {@code learning_record} 表的 moment 字段，
     *       同时更新 {@code learning_lesson} 表的最新学习时间和最新小节。</li>
     * </ol>
     *
     * <p><b>多线程安全性说明：</b></p>
     * <ul>
     *   <li>多个线程同时 poll 同一个 DelayQueue，DelayQueue 内部用 ReentrantLock 保证
     *       同一个任务不会被两条线程取走。</li>
     *   <li>Redis 读取是只读操作，多线程并发读同一个 key 完全安全。</li>
     *   <li>数据库 UPDATE 操作：MyBatis-Plus 的 {@code updateById} 是单条更新，
     *       即使两个线程同时更新同一条学习记录，也是两次串行执行的 UPDATE 语句，最终结果一致。</li>
     * </ul>
     *
     * <p><b>异常处理：</b></p>
     * <p>如果线程在 poll 或数据处理期间被中断（收到 {@link InterruptedException}），
     * 表示容器正在执行关闭（{@code shutdownNow()} 发出了中断信号），此时应退出循环。
     * 中断状态已在 catch 块中通过 break 处理，不做额外恢复。</p>
     */
    public void handleDelayTask() {
        // 循环条件：只要 running 为 true 就继续消费。
        // 当 destroy() 将 running 设为 false 后，线程在下一次 poll 超时返回后退出。
        while (running.get()) {
            try {
                // ========== 步骤1：获取延迟任务 ==========
                // 关键设计：使用 poll(timeout) 而非 take()。
                // - take() 会无限期阻塞，即使 running 被设为 false 也无法感知，线程无法优雅退出。
                // - poll(1, SECONDS) 最多等待1秒，超时返回 null，线程回到 while 检查 running 标志。
                //   1秒的超时对20秒级别的业务延迟来说影响可忽略不计。
                DelayTask<RecordTaskData> task = queue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (task == null) {
                    // 超时，没有任务可处理，回到 while 循环检查是否需要退出。
                    continue;
                }

                // ========== 步骤2：提取任务数据 ==========
                // RecordTaskData 包含 lessonId、sectionId 和 moment（任务创建时的播放进度）。
                RecordTaskData data = task.getData();

                // ========== 步骤3：查询 Redis 缓存 ==========
                // 从 Redis Hash 中读取该 (lessonId, sectionId) 对应的最新播放进度。
                // 如果返回 null，说明缓存已过期或被清理（比如用户已经学完了该小节），跳过处理。
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                if (record == null) {
                    log.debug("缓存未命中! lessonId={}, sectionId={}", data.getLessonId(), data.getSectionId());
                    continue;
                }

                // ========== 步骤4：比较 moment，判断用户是否仍在观看 ==========
                // 这是延迟队列机制的核心判断逻辑：
                // - 任务中的 moment 是20秒前用户上报时的播放进度。
                // - Redis 中的 moment 是当前最新的播放进度（可能在这20秒内被新的上报更新了）。
                // - 如果两者不一致 → 用户还在继续观看 → 丢弃本次旧数据，等下次延迟任务触发。
                // - 如果两者一致 → 用户已停止观看20秒 → 可以安全地将数据持久化到数据库。
                if (!Objects.equals(data.getMoment(), record.getMoment())) {
                    // 不一致：用户产生了新的播放进度上报，说明还在观看视频。
                    // 直接丢弃当前任务（不做数据库写入），减少对数据库的压力。
                    log.debug("moment不一致,跳过持久化. lessonId={}, sectionId={}, taskMoment={}, cacheMoment={}",
                            data.getLessonId(), data.getSectionId(), data.getMoment(), record.getMoment());
                    continue;
                }

                // ========== 步骤5：moment一致，持久化到数据库 ==========
                // 此时可以确认：用户在过去的20秒内没有产生新的播放进度上报，
                // 当前数据是"静止"的，可以安全地写入数据库。

                // 5.1 更新 learning_record 表：写入最新的播放进度（moment）。
                // 将 finishTime 置为 null（此时还未学完，学完的情况由调用方处理）。
                record.setFinishTime(null);
                recordMapper.updateById(record);

                // 5.2 更新 learning_lesson 表：更新用户课表的最新学习信息。
                // 包括：最新学习的小节ID（latestSectionId）和最新学习时间（latestLearnTime）。
                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());
                lesson.setLatestSectionId(data.getSectionId());
                lesson.setLatestLearnTime(LocalDateTime.now());
                lessonService.updateById(lesson);

                log.debug("学习记录持久化完成. lessonId={}, sectionId={}, moment={}",
                        data.getLessonId(), data.getSectionId(), data.getMoment());

            } catch (InterruptedException e) {
                // 线程被中断，通常发生在 destroy() → shutdownNow() 执行期间。
                // 此时应该退出循环，让线程结束。不做 interrupt() 恢复是因为该线程即将终止，
                // 没有上层调用者需要感知中断状态。
                log.error("延迟任务消费者线程被中断, 即将退出. 线程名={}", Thread.currentThread().getName(), e);
                break;
            }
        }
        log.info("延迟任务消费者线程退出. 线程名={}", Thread.currentThread().getName());
    }


    /**
     * <h3>添加学习记录延迟任务</h3>
     * <p>由业务层调用（{@code LearningRecordServiceImpl}），将用户的播放进度
     * 同时写入 Redis 缓存和延迟队列。</p>
     *
     * <p><b>操作步骤：</b></p>
     * <ol>
     *   <li>将学习记录写入 Redis Hash（供延迟任务消费时比对 moment 使用）。</li>
     *   <li>将学习记录包装为 {@link DelayTask}，放入延迟队列，延迟20秒后触发消费。</li>
     * </ol>
     *
     * <p><b>延迟时间为什么选 20 秒？</b></p>
     * <p>这是一个业务权衡：
     * <ul>
     *   <li>太短（如5秒）→ 用户只是暂停了一下就被判定为"离开"，频繁写库，失去延迟队列的意义。</li>
     *   <li>太长（如60秒）→ 用户关闭浏览器后，播放进度要等60秒才入库，用户体验差。</li>
     *   <li>20秒是一个折中值：给用户足够的暂停缓冲时间，同时也不会让进度丢失太久。</li>
     * </ul>
     *
     * @param record 用户的当前学习记录（包含 lessonId、sectionId、moment 等信息）
     */
    public void addLearningRecordTask(LearningRecord record) {
        // 步骤1：将学习记录写入 Redis 缓存，供延迟任务消费时比对 moment。
        // Redis key 格式：learning:record:{lessonId}
        // Hash field：sectionId
        // Hash value：RecordCacheData 的 JSON 字符串（含 id、moment、finished）
        writeRecordIntoCache(record);

        // 步骤2：将学习记录的核心信息（lessonId、sectionId、moment）封装为 DelayTask，
        // 放入延迟队列，设置延迟时间为20秒。
        // 20秒后消费者线程会取出该任务，与 Redis 中的最新数据比对，决定是否写库。
        queue.add(new DelayTask<>(new RecordTaskData(record), Duration.ofSeconds(20L)));
    }


    /**
     * <h3>将学习记录写入 Redis 缓存</h3>
     * <p>使用 Redis 的 Hash 数据结构存储，结构如下：</p>
     * <pre>
     * Key: learning:record:{lessonId}
     * Field: {sectionId}
     * Value: {"id": xxx, "moment": 120, "finished": false}  (JSON 字符串)
     * </pre>
     * <p>同时设置整个 Hash 的过期时间为 30 秒，防止僵尸数据长期占用 Redis 内存。
     * 30秒 > 延迟队列的20秒延迟，确保任务消费时缓存还未过期。</p>
     *
     * @param record 用户的当前学习记录
     */
    public void writeRecordIntoCache(LearningRecord record) {
        log.debug("更新学习记录的缓存数据!");

        try {
            // 数据转换：将 PO 转换为只包含缓存必要字段的 RecordCacheData 对象，
            // 然后序列化为 JSON 字符串。只缓存必要字段可以减少 Redis 内存占用。
            String json = JsonUtils.toJsonStr(new RecordCacheData(record));
            // 写入 Redis Hash
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId());
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
            // 重置整个 Hash 的过期时间（每次写入都刷新，保证活跃用户的数据不会过期）
            redisTemplate.expire(key, Duration.ofSeconds(30L));
        } catch (Exception e) {
            log.error("更新学习记录缓存出现异常!", e);
        }
    }

    /**
     * <h3>读取 Redis 中的学习记录缓存</h3>
     * <p>从 Redis Hash 中查询指定 (lessonId, sectionId) 对应的播放进度。</p>
     *
     * @param lessonId  课程ID
     * @param sectionId 小节ID
     * @return 缓存中的 LearningRecord 对象，如果未命中则返回 null
     */
    public LearningRecord readRecordCache(Long lessonId, Long sectionId) {
        try {
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            // 从 Hash 中获取指定 field 的值，返回的是一个 JSON 字符串或 null
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {
                return null;
            }
            // 将 JSON 字符串反序列化为 LearningRecord 对象
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            // 异常视为缓存未命中（比如 JSON 解析失败、Redis 连接异常等）
            log.error("缓存读取异常!", e);
            return null;
        }
    }

    /**
     * <h3>清理 Redis 中的学习记录缓存</h3>
     * <p>当用户完成某个小节的学习（第一次学完）时调用，删除对应的 Redis 缓存数据。</p>
     * <p>仅删除指定 sectionId 的 field，不删除整个 Hash key（该课程下可能还有其他小节的缓存）。</p>
     *
     * @param lessonId  课程ID
     * @param sectionId 小节ID
     */
    public void cleanRecordCache(Long lessonId, Long sectionId) {
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }


    /**
     * Redis 缓存中存储的数据结构。
     * <p>注意：这里不存储完整的 LearningRecord 对象，只选取延迟任务需要的核心字段：
     * 记录ID（id）、播放进度（moment）、是否学完（finished）。减少 Redis 内存占用。</p>
     */
    @Data
    @NoArgsConstructor
    private static class RecordCacheData {
        /** 学习记录的主键ID */
        private Long id;
        /** 视频观看时长（单位：秒） */
        private Integer moment;
        /** 是否第一次学完该小节（true=已学完，false或null=未学完） */
        private Boolean finished;

        /**
         * 从 LearningRecord PO 构造缓存数据对象。
         * 只提取缓存需要的字段，不做全量拷贝。
         */
        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }

    /**
     * 延迟任务中携带的业务数据。
     * <p>存储创建任务时的核心信息，供延迟任务消费时比对使用。
     * 包含 lessonId 和 sectionId（用于定位 Redis 缓存中的最新数据）
     * 以及 moment（用于判断用户是否仍在观看）。</p>
     */
    @Data
    @NoArgsConstructor
    private static class RecordTaskData {
        /** 课程ID */
        private Long lessonId;
        /** 小节ID */
        private Long sectionId;
        /** 任务创建时的播放进度（用于与 Redis 最新进度比对） */
        private Integer moment;

        /**
         * 从 LearningRecord 构造任务数据。
         * 注意：这里的 moment 是快照值，创建后不会变化。
         * 延迟任务消费时会用这个快照值与 Redis 中的最新值比对。
         */
        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}
