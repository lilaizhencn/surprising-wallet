package com.surprising.wallet.service;

import com.surprising.wallet.common.security.PasswordHashes;
import org.springframework.stereotype.Service;

/** Console password operations use the shared PBKDF2 format; credentials remain in the wallet domain. */
@Service
public class CustodyPasswordService {
    private final PasswordHashes hashes = new PasswordHashes();
    public String hash(String password) { return hashes.hash(password); }
    public boolean verify(String password,String encoded) { return hashes.verify(password,encoded); }
}
