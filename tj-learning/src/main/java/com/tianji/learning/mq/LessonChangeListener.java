package com.tianji.learning.mq;


import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class LessonChangeListener {

    private final ILearningLessonService lessonService;

    //监听用户课程支付是否成功,用mq来监听
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.pay.quene", durable = "true"), //队列持久化
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),//direct类型用于精确匹配,topic按照主题等
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenLessonPay(OrderBasicDTO order){
        //1.先做健壮性处理,判断是否为空
        if (order == null || order.getOrderId() == null || CollUtils.isEmpty(order.getCourseIds())){
            log.error("MQ消息有误,订单数据为空!");
            return;
        }

        /**
         * 幂等处理
         */
        //2.对收集到的消息进行去重处理,拿到消息中的课程id
        List<Long> courseIds = order.getCourseIds().stream()
                //distinct() 表示去重
                .distinct()
                .collect(Collectors.toList());

        //3.查询当前用户课表中已存在的课程id
        Set<Long> existCourseIds = lessonService.lambdaQuery()
                //只查询 course_id字段,不查询整行数据
                .select(LearningLesson::getCourseId)
                //条件1.查询当前用户
                .eq(LearningLesson::getUserId, order.getUserId())
                //条件2.只查本次订单包含的课程
                .in(LearningLesson::getCourseId, courseIds)
                //执行查询,返回LearningLesson对象的集合,同时转换成stream流
                .list().stream()
                //从LearningLesson对象的集合中去除courseId,并转换成set
                .map(LearningLesson::getCourseId).collect(Collectors.toSet());

        //过滤已存在的课程id,只保留课表中还不存在的课程id
        courseIds = courseIds.stream()
                //如果课表中还不存在当前courseId,表明可以添加
                .filter(courseId -> !existCourseIds.contains(courseId))
                //把过滤后的id信息重新收集成list集合
                .collect(Collectors.toList());

        //如果过滤后courseIds为空,表明这条mq消息是重复消息
        if (CollUtils.isEmpty(courseIds)){
            log.info("订单{}重复消费,用户{}的课程{}已经添加到课表中,无需重复处理.",
                    order.getOrderId(), order.getUserId(), order.getCourseIds());
            return;
        }

        //4.此时接收到正确的MQ消息,此时进行课程的添加
        //根据userId和courseIds进行课程添加
        //日志记录
        log.debug("监听到用户{}的订单{}, 需要添加{}到课表中.",
                order.getUserId(), order.getOrderId(), order.getCourseIds());
        //此时要使用过滤后的courseIds
        lessonService.addUserLessons(order.getUserId(), courseIds);

    }


    //监听用户退款是否成功
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.refund.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    public void listenCourseRefund(OrderBasicDTO order){
        //此处不需要做幂等,deleteCourseFromLesson中的删除操作天然幂等

        //健壮性判断
        if (order == null || order.getOrderId() == null || order.getUserId() == null || CollUtils.isEmpty(order.getCourseIds())){
            log.error("MQ消息有误,数据为空!");
            return;
        }

        //调用删除方法,这里需要主动传当前用户信息,因为这不是用户主动删除的.
        lessonService.deleteCourseFromLesson(order.getUserId(), order.getCourseIds().get(0));
        log.debug("已成功删除用户{}订单号为{}的订单", order.getUserId(), order.getOrderId());
    }

}
