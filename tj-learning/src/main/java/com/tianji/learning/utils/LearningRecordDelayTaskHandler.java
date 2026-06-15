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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;


@Slf4j
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper;
    private final ILearningLessonService lessonService;

    //创建延迟队列
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();

    //定义redis键的格式
    private final static String RECORD_KEY_TEMPLATE = "learning:record:{}";

    private static volatile boolean begin = true;

    @PostConstruct
    public void init(){
        CompletableFuture.runAsync(this::handleDelayTask);
    }

    @PreDestroy
    public void destroy(){
        begin = false;
        log.error("延迟任务停止执行!");
    }

    public void handleDelayTask(){
        while (begin){
            try {
                //1.获取延迟任务
                DelayTask<RecordTaskData> task = queue.take();
                RecordTaskData data = task.getData();
                //2.查询redis缓存
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                if (record == null) {
                    log.debug("缓存未命中!");
                    continue;
                }
                //3.进行比较 moment
                if (!Objects.equals(data.getMoment(), record.getMoment())){
                    //3.1 不一致,说明用户仍然在看视频,放弃旧数据
                    continue;
                }
                //3.2 一致,持久化播放进度数据到数据库
                //3.2.1更新学习记录的moment
                record.setFinishTime(null);
                recordMapper.updateById(record);
                //3.2.2更新课表的学习信息
                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());
                lesson.setLatestSectionId(data.getSectionId());
                lesson.setLatestLearnTime(LocalDateTime.now());

                lessonService.updateById(lesson);

            } catch (InterruptedException e) {
                log.error("处理延迟任务异常!", e);
            }
        }
    }


    //1.将学习记录缓存到redis和延迟队列之中
    public void addLearningRecordTask(LearningRecord record){
        //1.1将学习记录写到redis中
        writeRecordIntoCache(record);

        //1.2将学习记录写到延迟队列中
        queue.add(new DelayTask<>(new RecordTaskData(record), Duration.ofSeconds(20L)));
    }


    /**
     * 将学习记录缓存到redis中
     * @param record
     */
    public void writeRecordIntoCache(LearningRecord record) {
        log.debug("更新学习记录的缓存数据!");

        try {
            //1.数据转换
            String json = JsonUtils.toJsonStr(new RecordCacheData(record));
            //2.写入redis
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId());
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
            //3.添加过期时间
            redisTemplate.expire(key, Duration.ofSeconds(30L));
        } catch (Exception e) {
            log.error("更新学习记录缓存出现异常!", e);
        }
    }

    /**
     * 读取redis中的数据
     * @param lessonId
     * @param sectionId
     * @return
     */
    public LearningRecord readRecordCache(Long lessonId, Long sectionId){
        try {
            //1.读取redis数据
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            //拿到的是一个json对象,或者为null
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if(cacheData == null){
                return null;
            }

            //2.数据转换
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            //如果出现异常,代表缓存未命中
            log.error("缓存读取异常!", e);
            return null;
        }
    }

    public void cleanRecordCache(Long lessonId, Long sectionId){
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }




    @Data
    @NoArgsConstructor
    private static class RecordCacheData{
        //课程id
        private Long id;
        //视频观看时长
        private Integer moment;
        //是否学完
        private Boolean finished;

        //将record这个PO转换成redis中的存储的对象
        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }

    @Data
    @NoArgsConstructor
    private static class RecordTaskData{
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}
