package com.surprising.wallet.approval.model;

import java.util.UUID;

/** Independent public registry; contains neither share material nor a private key. */
public record RegisteredMpcKey(UUID tenantId,UUID keyId,String keyGroup,String algorithm,int epoch,
        String publicKeyHex,String publicAddress,String state) { }
