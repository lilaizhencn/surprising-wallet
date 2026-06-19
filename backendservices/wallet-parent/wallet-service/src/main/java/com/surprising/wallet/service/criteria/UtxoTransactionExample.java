package com.surprising.wallet.service.criteria;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 查询条件example类
 *
 * @author lilaizhen
 * @date 2018-03-31
 */
public class UtxoTransactionExample {
    protected String orderByClause;

    protected boolean distinct;

    protected List<Criteria> oredCriteria;

    public UtxoTransactionExample() {
        this.oredCriteria = new ArrayList<>();
    }

    public void setOrderByClause(final String orderByClause) {
        this.orderByClause = orderByClause;
    }

    public String getOrderByClause() {
        return this.orderByClause;
    }

    public void setDistinct(final boolean distinct) {
        this.distinct = distinct;
    }

    public boolean isDistinct() {
        return this.distinct;
    }

    public List<Criteria> getOredCriteria() {
        return this.oredCriteria;
    }

    public void or(final Criteria criteria) {
        this.oredCriteria.add(criteria);
    }

    public Criteria or() {
        final Criteria criteria = this.createCriteriaInternal();
        this.oredCriteria.add(criteria);
        return criteria;
    }

    public Criteria createCriteria() {
        final Criteria criteria = this.createCriteriaInternal();
        if (this.oredCriteria.size() == 0) {
            this.oredCriteria.add(criteria);
        }
        return criteria;
    }

    protected Criteria createCriteriaInternal() {
        final Criteria criteria = new Criteria();
        return criteria;
    }

    public void clear() {
        this.oredCriteria.clear();
        this.orderByClause = null;
        this.distinct = false;
    }

    protected abstract static class GeneratedCriteria {
        protected List<Criterion> criteria;

        protected GeneratedCriteria() {
            super();
            this.criteria = new ArrayList<>();
        }

        public boolean isValid() {
            return this.criteria.size() > 0;
        }

        public List<Criterion> getAllCriteria() {
            return this.criteria;
        }

        public List<Criterion> getCriteria() {
            return this.criteria;
        }

        protected void addCriterion(final String condition) {
            if (condition == null) {
                throw new RuntimeException("Value for condition cannot be null");
            }
            this.criteria.add(new Criterion(condition));
        }

        protected void addCriterion(final String condition, final Object value, final String property) {
            if (value == null) {
                throw new RuntimeException("Value for " + property + " cannot be null");
            }
            this.criteria.add(new Criterion(condition, value));
        }

        protected void addCriterion(final String condition, final Object value1, final Object value2, final String property) {
            if (value1 == null || value2 == null) {
                throw new RuntimeException("Between values for " + property + " cannot be null");
            }
            this.criteria.add(new Criterion(condition, value1, value2));
        }

        public Criteria andIdIsNull() {
            this.addCriterion("id is null");
            return (Criteria) this;
        }

        public Criteria andIdIsNotNull() {
            this.addCriterion("id is not null");
            return (Criteria) this;
        }

        public Criteria andIdEqualTo(final Long value) {
            this.addCriterion("id =", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotEqualTo(final Long value) {
            this.addCriterion("id <>", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdIn(final List<Long> values) {
            this.addCriterion("id in", values, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotIn(final List<Long> values) {
            this.addCriterion("id not in", values, "id");
            return (Criteria) this;
        }

        public Criteria andIdBetween(final Long value1, final Long value2) {
            this.addCriterion("id between", value1, value2, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotBetween(final Long value1, final Long value2) {
            this.addCriterion("id not between", value1, value2, "id");
            return (Criteria) this;
        }

        public Criteria andIdGreaterThan(final Long value) {
            this.addCriterion("id >", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdGreaterThanOrEqualTo(final Long value) {
            this.addCriterion("id >=", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdLessThan(final Long value) {
            this.addCriterion("id <", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdLessThanOrEqualTo(final Long value) {
            this.addCriterion("id <=", value, "id");
            return (Criteria) this;
        }

        public Criteria andTxIdIsNull() {
            this.addCriterion("tx_id is null");
            return (Criteria) this;
        }

        public Criteria andTxIdIsNotNull() {
            this.addCriterion("tx_id is not null");
            return (Criteria) this;
        }

        public Criteria andTxIdEqualTo(final String value) {
            this.addCriterion("tx_id =", value, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdNotEqualTo(final String value) {
            this.addCriterion("tx_id <>", value, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdIn(final List<String> values) {
            this.addCriterion("tx_id in", values, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdNotIn(final List<String> values) {
            this.addCriterion("tx_id not in", values, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdBetween(final String value1, final String value2) {
            this.addCriterion("tx_id between", value1, value2, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdNotBetween(final String value1, final String value2) {
            this.addCriterion("tx_id not between", value1, value2, "txId");
            return (Criteria) this;
        }


        public Criteria andTxIdLike(final String value) {
            this.addCriterion("tx_id like", value, "txId");
            return (Criteria) this;
        }

        public Criteria andTxIdNotLike(final String value) {
            this.addCriterion("tx_id not like", value, "txId");
            return (Criteria) this;
        }

        public Criteria andBlockHeightIsNull() {
            this.addCriterion("block_height is null");
            return (Criteria) this;
        }

        public Criteria andBlockHeightIsNotNull() {
            this.addCriterion("block_height is not null");
            return (Criteria) this;
        }

        public Criteria andBlockHeightEqualTo(final Long value) {
            this.addCriterion("block_height =", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightNotEqualTo(final Long value) {
            this.addCriterion("block_height <>", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightIn(final List<Long> values) {
            this.addCriterion("block_height in", values, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightNotIn(final List<Long> values) {
            this.addCriterion("block_height not in", values, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightBetween(final Long value1, final Long value2) {
            this.addCriterion("block_height between", value1, value2, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightNotBetween(final Long value1, final Long value2) {
            this.addCriterion("block_height not between", value1, value2, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightGreaterThan(final Long value) {
            this.addCriterion("block_height >", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightGreaterThanOrEqualTo(final Long value) {
            this.addCriterion("block_height >=", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightLessThan(final Long value) {
            this.addCriterion("block_height <", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andBlockHeightLessThanOrEqualTo(final Long value) {
            this.addCriterion("block_height <=", value, "blockHeight");
            return (Criteria) this;
        }

        public Criteria andAddressIsNull() {
            this.addCriterion("address is null");
            return (Criteria) this;
        }

        public Criteria andAddressIsNotNull() {
            this.addCriterion("address is not null");
            return (Criteria) this;
        }

        public Criteria andAddressEqualTo(final String value) {
            this.addCriterion("address =", value, "address");
            return (Criteria) this;
        }

        public Criteria andAddressNotEqualTo(final String value) {
            this.addCriterion("address <>", value, "address");
            return (Criteria) this;
        }

        public Criteria andAddressIn(final List<String> values) {
            this.addCriterion("address in", values, "address");
            return (Criteria) this;
        }

        public Criteria andAddressNotIn(final List<String> values) {
            this.addCriterion("address not in", values, "address");
            return (Criteria) this;
        }

        public Criteria andAddressBetween(final String value1, final String value2) {
            this.addCriterion("address between", value1, value2, "address");
            return (Criteria) this;
        }

        public Criteria andAddressNotBetween(final String value1, final String value2) {
            this.addCriterion("address not between", value1, value2, "address");
            return (Criteria) this;
        }


        public Criteria andAddressLike(final String value) {
            this.addCriterion("address like", value, "address");
            return (Criteria) this;
        }

        public Criteria andAddressNotLike(final String value) {
            this.addCriterion("address not like", value, "address");
            return (Criteria) this;
        }

        public Criteria andBalanceIsNull() {
            this.addCriterion("balance is null");
            return (Criteria) this;
        }

        public Criteria andBalanceIsNotNull() {
            this.addCriterion("balance is not null");
            return (Criteria) this;
        }

        public Criteria andBalanceEqualTo(final BigDecimal value) {
            this.addCriterion("balance =", value, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceNotEqualTo(final BigDecimal value) {
            this.addCriterion("balance <>", value, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceIn(final List<BigDecimal> values) {
            this.addCriterion("balance in", values, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceNotIn(final List<BigDecimal> values) {
            this.addCriterion("balance not in", values, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceBetween(final BigDecimal value1, final BigDecimal value2) {
            this.addCriterion("balance between", value1, value2, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceNotBetween(final BigDecimal value1, final BigDecimal value2) {
            this.addCriterion("balance not between", value1, value2, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceGreaterThan(final BigDecimal value) {
            this.addCriterion("balance >", value, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceGreaterThanOrEqualTo(final BigDecimal value) {
            this.addCriterion("balance >=", value, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceLessThan(final BigDecimal value) {
            this.addCriterion("balance <", value, "balance");
            return (Criteria) this;
        }

        public Criteria andBalanceLessThanOrEqualTo(final BigDecimal value) {
            this.addCriterion("balance <=", value, "balance");
            return (Criteria) this;
        }

        public Criteria andConfirmNumIsNull() {
            this.addCriterion("confirm_num is null");
            return (Criteria) this;
        }

        public Criteria andConfirmNumIsNotNull() {
            this.addCriterion("confirm_num is not null");
            return (Criteria) this;
        }

        public Criteria andConfirmNumEqualTo(final Long value) {
            this.addCriterion("confirm_num =", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumNotEqualTo(final Long value) {
            this.addCriterion("confirm_num <>", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumIn(final List<Long> values) {
            this.addCriterion("confirm_num in", values, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumNotIn(final List<Long> values) {
            this.addCriterion("confirm_num not in", values, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumBetween(final Long value1, final Long value2) {
            this.addCriterion("confirm_num between", value1, value2, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumNotBetween(final Long value1, final Long value2) {
            this.addCriterion("confirm_num not between", value1, value2, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumGreaterThan(final Long value) {
            this.addCriterion("confirm_num >", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumGreaterThanOrEqualTo(final Long value) {
            this.addCriterion("confirm_num >=", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumLessThan(final Long value) {
            this.addCriterion("confirm_num <", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andConfirmNumLessThanOrEqualTo(final Long value) {
            this.addCriterion("confirm_num <=", value, "confirmNum");
            return (Criteria) this;
        }

        public Criteria andSeqIsNull() {
            this.addCriterion("seq is null");
            return (Criteria) this;
        }

        public Criteria andSeqIsNotNull() {
            this.addCriterion("seq is not null");
            return (Criteria) this;
        }

        public Criteria andSeqEqualTo(final Short value) {
            this.addCriterion("seq =", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqNotEqualTo(final Short value) {
            this.addCriterion("seq <>", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqIn(final List<Short> values) {
            this.addCriterion("seq in", values, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqNotIn(final List<Short> values) {
            this.addCriterion("seq not in", values, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqBetween(final Short value1, final Short value2) {
            this.addCriterion("seq between", value1, value2, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqNotBetween(final Short value1, final Short value2) {
            this.addCriterion("seq not between", value1, value2, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqGreaterThan(final Short value) {
            this.addCriterion("seq >", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqGreaterThanOrEqualTo(final Short value) {
            this.addCriterion("seq >=", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqLessThan(final Short value) {
            this.addCriterion("seq <", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSeqLessThanOrEqualTo(final Short value) {
            this.addCriterion("seq <=", value, "seq");
            return (Criteria) this;
        }

        public Criteria andSpentIsNull() {
            this.addCriterion("spent is null");
            return (Criteria) this;
        }

        public Criteria andSpentIsNotNull() {
            this.addCriterion("spent is not null");
            return (Criteria) this;
        }

        public Criteria andSpentEqualTo(final Byte value) {
            this.addCriterion("spent =", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentNotEqualTo(final Byte value) {
            this.addCriterion("spent <>", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentIn(final List<Byte> values) {
            this.addCriterion("spent in", values, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentNotIn(final List<Byte> values) {
            this.addCriterion("spent not in", values, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentBetween(final Byte value1, final Byte value2) {
            this.addCriterion("spent between", value1, value2, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentNotBetween(final Byte value1, final Byte value2) {
            this.addCriterion("spent not between", value1, value2, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentGreaterThan(final Byte value) {
            this.addCriterion("spent >", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentGreaterThanOrEqualTo(final Byte value) {
            this.addCriterion("spent >=", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentLessThan(final Byte value) {
            this.addCriterion("spent <", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentLessThanOrEqualTo(final Byte value) {
            this.addCriterion("spent <=", value, "spent");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdIsNull() {
            this.addCriterion("spent_tx_id is null");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdIsNotNull() {
            this.addCriterion("spent_tx_id is not null");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdEqualTo(final String value) {
            this.addCriterion("spent_tx_id =", value, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdNotEqualTo(final String value) {
            this.addCriterion("spent_tx_id <>", value, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdIn(final List<String> values) {
            this.addCriterion("spent_tx_id in", values, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdNotIn(final List<String> values) {
            this.addCriterion("spent_tx_id not in", values, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdBetween(final String value1, final String value2) {
            this.addCriterion("spent_tx_id between", value1, value2, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdNotBetween(final String value1, final String value2) {
            this.addCriterion("spent_tx_id not between", value1, value2, "spentTxId");
            return (Criteria) this;
        }


        public Criteria andSpentTxIdLike(final String value) {
            this.addCriterion("spent_tx_id like", value, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andSpentTxIdNotLike(final String value) {
            this.addCriterion("spent_tx_id not like", value, "spentTxId");
            return (Criteria) this;
        }

        public Criteria andCreateDateIsNull() {
            this.addCriterion("create_date is null");
            return (Criteria) this;
        }

        public Criteria andCreateDateIsNotNull() {
            this.addCriterion("create_date is not null");
            return (Criteria) this;
        }

        public Criteria andCreateDateEqualTo(final Date value) {
            this.addCriterion("create_date =", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateNotEqualTo(final Date value) {
            this.addCriterion("create_date <>", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateIn(final List<Date> values) {
            this.addCriterion("create_date in", values, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateNotIn(final List<Date> values) {
            this.addCriterion("create_date not in", values, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateBetween(final Date value1, final Date value2) {
            this.addCriterion("create_date between", value1, value2, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateNotBetween(final Date value1, final Date value2) {
            this.addCriterion("create_date not between", value1, value2, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateGreaterThan(final Date value) {
            this.addCriterion("create_date >", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateGreaterThanOrEqualTo(final Date value) {
            this.addCriterion("create_date >=", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateLessThan(final Date value) {
            this.addCriterion("create_date <", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andCreateDateLessThanOrEqualTo(final Date value) {
            this.addCriterion("create_date <=", value, "createDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateIsNull() {
            this.addCriterion("update_date is null");
            return (Criteria) this;
        }

        public Criteria andUpdateDateIsNotNull() {
            this.addCriterion("update_date is not null");
            return (Criteria) this;
        }

        public Criteria andUpdateDateEqualTo(final Date value) {
            this.addCriterion("update_date =", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateNotEqualTo(final Date value) {
            this.addCriterion("update_date <>", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateIn(final List<Date> values) {
            this.addCriterion("update_date in", values, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateNotIn(final List<Date> values) {
            this.addCriterion("update_date not in", values, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateBetween(final Date value1, final Date value2) {
            this.addCriterion("update_date between", value1, value2, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateNotBetween(final Date value1, final Date value2) {
            this.addCriterion("update_date not between", value1, value2, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateGreaterThan(final Date value) {
            this.addCriterion("update_date >", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateGreaterThanOrEqualTo(final Date value) {
            this.addCriterion("update_date >=", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateLessThan(final Date value) {
            this.addCriterion("update_date <", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andUpdateDateLessThanOrEqualTo(final Date value) {
            this.addCriterion("update_date <=", value, "updateDate");
            return (Criteria) this;
        }

        public Criteria andBizIsNull() {
            this.addCriterion("biz is null");
            return (Criteria) this;
        }

        public Criteria andBizIsNotNull() {
            this.addCriterion("biz is not null");
            return (Criteria) this;
        }

        public Criteria andBizEqualTo(final Integer value) {
            this.addCriterion("biz =", value, "biz");
            return (Criteria) this;
        }

        public Criteria andBizNotEqualTo(final Integer value) {
            this.addCriterion("biz <>", value, "biz");
            return (Criteria) this;
        }

        public Criteria andBizIn(final List<Integer> values) {
            this.addCriterion("biz in", values, "biz");
            return (Criteria) this;
        }

        public Criteria andBizNotIn(final List<Integer> values) {
            this.addCriterion("biz not in", values, "biz");
            return (Criteria) this;
        }

        public Criteria andBizBetween(final Integer value1, final Integer value2) {
            this.addCriterion("biz between", value1, value2, "biz");
            return (Criteria) this;
        }

        public Criteria andBizNotBetween(final Integer value1, final Integer value2) {
            this.addCriterion("biz not between", value1, value2, "biz");
            return (Criteria) this;
        }

        public Criteria andBizGreaterThan(final Integer value) {
            this.addCriterion("biz >", value, "biz");
            return (Criteria) this;
        }

        public Criteria andBizGreaterThanOrEqualTo(final Integer value) {
            this.addCriterion("biz >=", value, "biz");
            return (Criteria) this;
        }

        public Criteria andBizLessThan(final Integer value) {
            this.addCriterion("biz <", value, "biz");
            return (Criteria) this;
        }

        public Criteria andBizLessThanOrEqualTo(final Integer value) {
            this.addCriterion("biz <=", value, "biz");
            return (Criteria) this;
        }

        public Criteria andStatusIsNull() {
            this.addCriterion("status is null");
            return (Criteria) this;
        }

        public Criteria andStatusIsNotNull() {
            this.addCriterion("status is not null");
            return (Criteria) this;
        }

        public Criteria andStatusEqualTo(final Byte value) {
            this.addCriterion("status =", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotEqualTo(final Byte value) {
            this.addCriterion("status <>", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusIn(final List<Byte> values) {
            this.addCriterion("status in", values, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotIn(final List<Byte> values) {
            this.addCriterion("status not in", values, "status");
            return (Criteria) this;
        }

        public Criteria andStatusBetween(final Byte value1, final Byte value2) {
            this.addCriterion("status between", value1, value2, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotBetween(final Byte value1, final Byte value2) {
            this.addCriterion("status not between", value1, value2, "status");
            return (Criteria) this;
        }

        public Criteria andStatusGreaterThan(final Byte value) {
            this.addCriterion("status >", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusGreaterThanOrEqualTo(final Byte value) {
            this.addCriterion("status >=", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusLessThan(final Byte value) {
            this.addCriterion("status <", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusLessThanOrEqualTo(final Byte value) {
            this.addCriterion("status <=", value, "status");
            return (Criteria) this;
        }

    }

    public static class Criteria extends GeneratedCriteria {

        protected Criteria() {
            super();
        }

        public Criteria andFieldLike(final String fieldName, final String keyword) {
            this.addCriterion(fieldName + " like ", keyword, fieldName);
            return this;
        }
    }

    public static class Criterion {
        private final String condition;

        private Object value;

        private Object secondValue;

        private boolean noValue;

        private boolean singleValue;

        private boolean betweenValue;

        private boolean listValue;

        private final String typeHandler;

        public String getCondition() {
            return this.condition;
        }

        public Object getValue() {
            return this.value;
        }

        public Object getSecondValue() {
            return this.secondValue;
        }

        public boolean isNoValue() {
            return this.noValue;
        }

        public boolean isSingleValue() {
            return this.singleValue;
        }

        public boolean isBetweenValue() {
            return this.betweenValue;
        }

        public boolean isListValue() {
            return this.listValue;
        }

        public String getTypeHandler() {
            return this.typeHandler;
        }

        protected Criterion(final String condition) {
            super();
            this.condition = condition;
            this.typeHandler = null;
            this.noValue = true;
        }

        protected Criterion(final String condition, final Object value, final String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.typeHandler = typeHandler;
            if (value instanceof List<?>) {
                this.listValue = true;
            } else {
                this.singleValue = true;
            }
        }

        protected Criterion(final String condition, final Object value) {
            this(condition, value, null);
        }

        protected Criterion(final String condition, final Object value, final Object secondValue, final String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.secondValue = secondValue;
            this.typeHandler = typeHandler;
            this.betweenValue = true;
        }

        protected Criterion(final String condition, final Object value, final Object secondValue) {
            this(condition, value, secondValue, null);
        }
    }
}