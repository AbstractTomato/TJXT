package com.tianji.learning.mq;


import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

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
        //todo 需要做幂等处理
        if (order == null || order.getOrderId() == null || CollUtils.isEmpty(order.getCourseIds())){
            log.error("MQ消息有误,订单数据为空!");
            return;
        }

        //2.此时接收到正确的MQ消息,此时进行课程的添加
        //根据userId和courseIds进行课程添加

        //日志记录
        log.debug("监听到用户{}的订单{}, 需要添加{}到课表中.",
                order.getUserId(), order.getOrderId(), order.getCourseIds());

        lessonService.addUserLessons(order.getUserId(), order.getCourseIds());


    }

}
