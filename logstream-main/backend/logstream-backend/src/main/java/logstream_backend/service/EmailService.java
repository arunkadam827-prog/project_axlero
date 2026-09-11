package logstream_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${alert.email.to}")
    private String recipientEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendAlertEmail(
            String ruleName,
            String message) {

        SimpleMailMessage email =
                new SimpleMailMessage();

        email.setTo(recipientEmail);

        email.setSubject(
                "LogStream Alert: " + ruleName
        );

        email.setText(
                "LogStream Alert\n\n"
                + "Rule: " + ruleName + "\n\n"
                + message
                + "\n\n"
                + "Please check the LogStream dashboard."
        );

        mailSender.send(email);
    }
}