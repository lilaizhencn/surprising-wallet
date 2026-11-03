package com.surprising.wallet.approval;

import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.approval.service.ApprovalEnrollmentService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The credential file remains on the trusted approval host; no password command-line argument is accepted. */
public final class ApprovalEnrollment {
    private ApprovalEnrollment() { }
    public static void enroll(Path file) throws Exception {
        var json=new ObjectMapper();
        var command=json.readValue(read(file),ApprovalEnrollmentService.Enrollment.class);
        var data=new DriverManagerDataSource(required("APPROVAL_DB_URL"),required("APPROVAL_DB_USERNAME"),required("APPROVAL_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(data);
        var service=new ApprovalEnrollmentService(new ApprovalTenantRepository(jdbc),new ApprovalMemberRepository(jdbc),new ApprovalAuditRepository(jdbc,json));
        new TransactionTemplate(new DataSourceTransactionManager(data)).executeWithoutResult(status->service.enroll(command));
        System.out.println("Independent approval member enrolled: "+command.memberId());
    }
    public static void inviteAdministrator(Path file) throws Exception {
        var json=new ObjectMapper();
        var command=json.readValue(read(file),ApprovalEnrollmentService.AdminInvitation.class);
        var data=new DriverManagerDataSource(required("APPROVAL_DB_URL"),required("APPROVAL_DB_USERNAME"),required("APPROVAL_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(data);
        var service=new ApprovalEnrollmentService(new ApprovalTenantRepository(jdbc),new ApprovalMemberRepository(jdbc),new ApprovalAuditRepository(jdbc,json));
        var activation=new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status->service.inviteAdministrator(command));
        System.out.println(activation.token());
    }
    private static byte[] read(Path file) throws Exception {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>16384
                || !Files.getOwner(file,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"))
                || !Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)))
            throw new IllegalArgumentException("enrollment requires a local owner-only 0600 regular file");
        return Files.readAllBytes(file);
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null) throw new IllegalStateException(name+" is required");return value;
    }
}
