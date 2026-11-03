package com.surprising.wallet.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Separate process and identity. Never component-scanned by the wallet application. */
@SpringBootApplication
public class ApprovalApplication {
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("enroll")) { ApprovalEnrollment.enroll(java.nio.file.Path.of(args[1]));return; }
        if(args.length==2 && args[0].equals("invite-admin")) { ApprovalEnrollment.inviteAdministrator(java.nio.file.Path.of(args[1]));return; }
        if(args.length>0 && args[0].equals("enroll")) throw new IllegalArgumentException("usage: java -jar wallet-approval.jar enroll /secure/member.json");
        if(args.length>0 && args[0].equals("invite-admin")) throw new IllegalArgumentException("usage: java -jar wallet-approval.jar invite-admin /secure/member.json");
        SpringApplication.run(ApprovalApplication.class, args);
    }
}
