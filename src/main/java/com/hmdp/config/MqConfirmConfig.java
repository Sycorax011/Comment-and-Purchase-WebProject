package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Slf4j
@Configuration
public class MqConfirmConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @PostConstruct
    public void initRabbitTemplate() {
        // 设置全局 ConfirmCallback（当前类实现了该接口）
        rabbitTemplate.setConfirmCallback(this);
        // 设置全局 ReturnsCallback（当前类也实现了该接口）
        rabbitTemplate.setReturnsCallback(this);

        rabbitTemplate.setMandatory(true);
    }

    //ConfirmCallback：负责确认交换机是否收到了消息
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String s) {
        if (ack) {
            log.info("全局Confirm回调成功：消息已到达交换机，ID: {}", correlationData.getId());
        } else {
            log.error("全局Confirm回调失败：消息未到达交换机，ID: {}，原因: {}", correlationData.getId(), s);
        }
    }

    //ReturnCallback：在交换机收到了消息之后，负责路由失败的返回结果
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        log.debug("收到消息后的Return CallBack, exchange:{}, key:{}, msg:{}, code:{}, text:{}",
                returned.getExchange(), returned.getRoutingKey(), returned.getMessage(),
                returned.getReplyCode(), returned.getReplyText());
    }
}
