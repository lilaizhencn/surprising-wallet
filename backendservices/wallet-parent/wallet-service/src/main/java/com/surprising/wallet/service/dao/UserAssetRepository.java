package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.pojo.UserAsset;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface UserAssetRepository {

    @Select("SELECT * FROM user_asset WHERE user_id = #{userId} AND currency = #{currency}")
    UserAsset selectByUserAndCurrency(@Param("userId") Long userId, @Param("currency") Integer currency);

    @Insert("INSERT INTO user_asset (user_id, currency, balance, frozen, version, create_date, update_date) " +
            "VALUES (#{userId}, #{currency}, #{balance}, #{frozen}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(UserAsset asset);

    @Update("UPDATE user_asset SET balance = #{balance}, frozen = #{frozen}, version = version + 1, update_date = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND balance = #{oldBalance} AND frozen = #{oldFrozen} AND version = #{version}")
    int updateBalanceWithLock(@Param("id") Long id, @Param("balance") BigDecimal balance,
                              @Param("frozen") BigDecimal frozen,
                              @Param("oldBalance") BigDecimal oldBalance,
                              @Param("oldFrozen") BigDecimal oldFrozen,
                              @Param("version") Integer version);

    @Update("UPDATE user_asset SET balance = balance - #{amount}, frozen = frozen - #{amount}, " +
            "version = version + 1, update_date = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND frozen >= #{amount} AND version = #{version}")
    int deductFrozen(@Param("id") Long id, @Param("amount") BigDecimal amount, @Param("version") Integer version);
}
