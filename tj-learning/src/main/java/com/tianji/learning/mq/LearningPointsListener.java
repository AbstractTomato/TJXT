package com.tianji.learning.mq;

import com.tianji.common.constants.MqConstants;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LearningPointsListener {

    private final IPointsRecordService recordService;

    /**
     * 监听回答问题获取积分的消息队列
     * @param message
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "qa.points.queue", durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_REPLY
    ))
    public void listenWriteReplyMessage(SignInMessage message){
        recordService.addPointsRecord(message, PointsRecordType.QA);
    }


    /**
     * 监听签到获取积分的消息队列
     * @param message
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "sign.points.queue", durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignInMessage(SignInMessage message){
        recordService.addPointsRecord(message, PointsRecordType.SIGN);
    }


    /**
     * 监听学习视频获取积分的消息队列
     * @param message
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "learning.points.queue", durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.LEARN_SECTION
    ))
    public void listenLearnSectionMessage(SignInMessage message){
        recordService.addPointsRecord(message, PointsRecordType.LEARNING);
    }



}
