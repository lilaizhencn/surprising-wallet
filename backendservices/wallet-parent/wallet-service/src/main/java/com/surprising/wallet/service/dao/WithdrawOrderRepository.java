package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.pojo.WithdrawOrder;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WithdrawOrderRepository {

    @Select("SELECT * FROM withdraw_order WHERE status = #{status} ORDER BY create_date ASC LIMIT #{limit}")
    List<WithdrawOrder> selectByStatus(@Param("status") Integer status, @Param("limit") Integer limit);

    @Insert("INSERT INTO withdraw_order (user_id, currency, chain, from_address, to_address, amount, fee, " +
            "status, signature_data, remark, create_date, update_date) " +
            "VALUES (#{userId}, #{currency}, #{chain}, #{fromAddress}, #{toAddress}, #{amount}, #{fee}, " +
            "0, #{signatureData}, #{remark}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(WithdrawOrder order);

    @Update("UPDATE withdraw_order SET status = #{status}, tx_id = #{txId}, signature_data = #{signatureData}, " +
            "update_date = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateSigned(@Param("id") Long id, @Param("status") Integer status,
                     @Param("txId") String txId, @Param("signatureData") String signatureData);

    @Select("SELECT * FROM withdraw_order WHERE id = #{id}")
    WithdrawOrder selectById(@Param("id") Long id);
}
