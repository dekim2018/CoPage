package com.copage.workspace.out;

import com.copage.workspace.app.InviteMailer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 로 초대 메일을 보낸다 (W-1, D-50). 요청을 붙잡지 않게 보내기는 따로 돈다. 실패하면 로그만 남긴다. 초대는 이미 만들어졌고
 * OWNER 의 화면에 링크가 있어 직접 건넬 수 있다.
 */
public final class SmtpInviteMailer implements InviteMailer {

    private static final System.Logger LOG = System.getLogger(SmtpInviteMailer.class.getName());
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final JavaMailSender sender;
    private final String from;
    private final String publicUrl;
    private final Executor executor;

    /** @param publicUrl 화면의 주소(https://도메인). 링크는 이 아래의 /invite/{token} 이다 */
    public SmtpInviteMailer(JavaMailSender sender, String from, String publicUrl, Executor executor) {
        this.sender = sender;
        this.from = from;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.executor = executor;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void send(Invitation invitation) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(invitation.email());
        message.setSubject("[CoPage] " + invitation.workspaceName() + " 워크스페이스에 초대되었습니다");
        message.setText("""
                %s 님이 CoPage 의 "%s" 워크스페이스에 초대했습니다.

                아래 링크를 열고 이 메일 주소(%s)로 가입하거나 로그인하면 들어갑니다.
                %s/invite/%s

                링크는 %s 까지 한 번만 쓸 수 있습니다. 초대받을 일이 없다면 이 메일은 무시해도 됩니다.
                """.formatted(invitation.inviterName(), invitation.workspaceName(), invitation.email(), publicUrl,
                invitation.token(), DATE.format(invitation.expiresAt())));
        executor.execute(() -> {
            try {
                sender.send(message);
            } catch (RuntimeException e) {
                // 받는 주소는 남기지 않는다(개인정보). 초대 ID 로 충분하지 않아 도메인만 남긴다.
                String domain = invitation.email().substring(invitation.email().indexOf('@') + 1);
                LOG.log(System.Logger.Level.WARNING, "초대 메일을 보내지 못했다(받는 도메인 " + domain + ")", e);
            }
        });
    }
}
