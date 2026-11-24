package com.copage.web;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.util.regex.Pattern;

/**
 * 에러 모니터링으로 보내기 전에 비밀을 가린다 (D-49). 주소에 실리는 WebSocket 티켓, 공유 링크와 초대의 토큰, Bearer 토큰이
 * 메시지나 요청 주소에 섞여 나가지 않게 한다. 접근 로그의 가리기(Caddyfile)와 같은 규칙이다.
 */
final class ErrorReportScrubber implements SentryOptions.BeforeSendCallback, SentryOptions.BeforeBreadcrumbCallback {

    private static final Pattern SECRETS = Pattern.compile(
            "(/(?:s|api/share|invite|api/invites)/)[^/?\\s\"']+|((?:[?&]|\\b)ticket=)[^&\\s\"']*|(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    static String scrub(String text) {
        return text == null ? null : SECRETS.matcher(text).replaceAll("$1$2$3REDACTED");
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(scrub(message.getMessage()));
            message.setFormatted(scrub(message.getFormatted()));
        }
        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(scrub(request.getUrl()));
            request.setQueryString(scrub(request.getQueryString()));
            request.setCookies(null);
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(scrub(exception.getValue()));
            }
        }
        if (event.getBreadcrumbs() != null) {
            event.getBreadcrumbs().forEach(this::scrubBreadcrumb);
        }
        return event;
    }

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        scrubBreadcrumb(breadcrumb);
        return breadcrumb;
    }

    private void scrubBreadcrumb(Breadcrumb breadcrumb) {
        breadcrumb.setMessage(scrub(breadcrumb.getMessage()));
        Object url = breadcrumb.getData("url");
        if (url instanceof String value) {
            breadcrumb.setData("url", scrub(value));
        }
    }
}
