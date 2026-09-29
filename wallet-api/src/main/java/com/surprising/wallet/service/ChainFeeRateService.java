package com.surprising.wallet.service;

import com.surprising.wallet.repository.ChainFeeRateRepository;
import org.springframework.stereotype.Service;

@Service
public class ChainFeeRateService {
    private final ChainFeeRateRepository repository;
    public ChainFeeRateService(ChainFeeRateRepository repository) { this.repository = repository; }
    public String get(String chain) { return repository.find(chain); }
    public void set(String chain, String rate) { repository.save(chain, rate); }
}
