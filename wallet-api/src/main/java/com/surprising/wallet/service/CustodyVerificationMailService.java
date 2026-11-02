package com.surprising.wallet.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Delivery is deliberately separate from the transaction preparing a one-use email challenge. */
@Service
@RequiredArgsConstructor
public class CustodyVerificationMailService {
    private final ObjectProvider<JavaMailSender> senders;
    private final Environment environment;

    public boolean configured() {
        return environment.getProperty("sw.wallet.custody.email-verification.enabled",Boolean.class,false)
                && !environment.getProperty("sw.wallet.custody.email-verification.from","").isBlank()
                && !environment.getProperty("spring.mail.host","").isBlank() && senders.getIfAvailable() != null;
    }

    public void send(String recipient, String code) {
        if (!configured()) throw new IllegalArgumentException("Email delivery is not configured");
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(environment.getRequiredProperty("sw.wallet.custody.email-verification.from"));
        mail.setTo(recipient);
        mail.setSubject("Surprising Wallet sign-in verification");
        mail.setText("Your verification code is " + code + ". It expires in 5 minutes. Do not share this code.");
        try { senders.getObject().send(mail); }
        catch (org.springframework.mail.MailException e) { throw new IllegalArgumentException("Email delivery failed; contact your administrator"); }
    }
}
