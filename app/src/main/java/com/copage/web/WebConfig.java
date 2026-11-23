package com.copage.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 모듈들이 함께 쓰는 웹 설정을 올린다. */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
class WebConfig {
}
