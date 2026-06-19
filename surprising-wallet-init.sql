/*
 Navicat Premium Data Transfer

 Source Server         : 47.100.126.227
 Source Server Type    : MySQL
 Source Server Version : 80020
 Source Host           : 47.100.126.227:5211
 Source Schema         : atomex-wallet

 Target Server Type    : MySQL
 Target Server Version : 80020
 File Encoding         : 65001

 Date: 18/07/2020 11:24:47
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `account_transaction`;
CREATE TABLE `account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for act_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `bat_account_transaction`;
CREATE TABLE `bat_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bat_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `bat_withdraw_record`;
CREATE TABLE `bat_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bat_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `bat_withdraw_transaction`;
CREATE TABLE `bat_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bch_address
-- ----------------------------
DROP TABLE IF EXISTS `bch_address`;
CREATE TABLE `bch_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=79 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bch_utxo_transaction
-- ----------------------------
DROP TABLE IF EXISTS `bch_utxo_transaction`;
CREATE TABLE `bch_utxo_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `seq` smallint unsigned NOT NULL COMMENT 'output序号',
  `spent` tinyint unsigned NOT NULL COMMENT '是否被花费',
  `status` tinyint DEFAULT '0' COMMENT '-1: 删除；0:提现中;1:签名中;2:已发送; 3:已确认',
  `spent_tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '花费此输出的txid',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`,`seq`) USING BTREE,
  KEY `spent_tx_id` (`spent_tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bch_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `bch_withdraw_record`;
CREATE TABLE `bch_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for bch_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `bch_withdraw_transaction`;
CREATE TABLE `bch_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for best_block_height
-- ----------------------------
DROP TABLE IF EXISTS `best_block_height`;
CREATE TABLE `best_block_height` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `currency` int unsigned NOT NULL,
  `height` bigint unsigned NOT NULL,
  `interval_time` bigint NOT NULL DEFAULT '300000' COMMENT '区块更新间隔时间，默认5分钟',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `currency` (`currency`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=44 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of best_block_height
-- ----------------------------
BEGIN;
INSERT INTO `best_block_height` VALUES (43, 1, 1635075, 300000, '2019-12-22 15:46:04', '2019-12-24 23:22:05');
COMMIT;

-- ----------------------------
-- Table structure for bkbt_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `btc_address`;
CREATE TABLE `btc_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=277 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of btc_address
-- ----------------------------
BEGIN;
INSERT INTO `btc_address` VALUES (274, 1, '2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys', 0.00000000, 1, 0, 1, 0, '2019-12-24 21:14:20', '1970-08-18 08:00:00');
INSERT INTO `btc_address` VALUES (275, 0, '2N6s5s8RHvEF4tcsd3AEXPyLe1KRzqZu6tM', 0.00000000, 0, 0, 0, 0, '2019-12-24 21:20:14', '1970-08-18 08:00:00');
INSERT INTO `btc_address` VALUES (276, 0, '2MyerbC1UfmrYciuGo22uCA59BJHkbc952K', 0.00000000, 0, 0, 1, 0, '2019-12-24 23:11:31', '1970-08-18 08:00:00');
COMMIT;

-- ----------------------------
-- Table structure for btc_utxo_transaction
-- ----------------------------
DROP TABLE IF EXISTS `btc_utxo_transaction`;
CREATE TABLE `btc_utxo_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `seq` smallint unsigned NOT NULL COMMENT 'output序号',
  `spent` tinyint unsigned NOT NULL COMMENT '是否被花费',
  `status` tinyint DEFAULT '0' COMMENT '-1: 删除；0:提现中;1:签名中;2:已发送; 3:已确认',
  `spent_tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '花费此输出的txid',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`,`seq`) USING BTREE,
  KEY `spent_tx_id` (`spent_tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=311 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of btc_utxo_transaction
-- ----------------------------
BEGIN;
INSERT INTO `btc_utxo_transaction` VALUES (310, '497f7e082c1f4190b0ecad1f47ac0216b7a8b5b8ffebee352ab9c0102e48f173', 1635056, '2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys', 0.00010000, 2, 1, 1, 1, 1, '127', '2019-12-24 21:16:34', '2019-12-24 23:11:31');
COMMIT;

-- ----------------------------
-- Table structure for btc_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `btc_withdraw_record`;
CREATE TABLE `btc_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=112 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of btc_withdraw_record
-- ----------------------------
BEGIN;
INSERT INTO `btc_withdraw_record` VALUES (111, '127', '2MsKXJEcPhqooNV13yVvzjKvMHBbyKYGJQx', 1, 0.00001000, 0.00000000, 1, 1, '224r210e-21f2-4305-2665-8505575fabfd15312072722ff', 1, '2019-12-24 21:20:11', '2019-12-24 23:11:32');
COMMIT;

-- ----------------------------
-- Table structure for btc_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `btc_withdraw_transaction`;
CREATE TABLE `btc_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=128 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of btc_withdraw_transaction
-- ----------------------------
BEGIN;
INSERT INTO `btc_withdraw_transaction` VALUES (120, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qn5w4kf3z60pha7ttsmww58dk4u7ftujq6zgfhmqqav4fxekfdufq3tngr9\",\"balance\":0E-8,\"biz\":0,\"createDate\":1576990225000,\"currency\":\"btc\",\"id\":1,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":0}],\"feePerKb\":3,\"changeAddress\":\"tb1qvtt3jdcufxnf2q9qenpplxyzrr8ceax8uzvea49dyexstn5gn9xqnxx8mv\",\"utxos\":[{\"address\":\"tb1qn5w4kf3z60pha7ttsmww58dk4u7ftujq6zgfhmqqav4fxekfdufq3tngr9\",\"balance\":0.00022000,\"biz\":0,\"blockHeight\":1633745,\"confirmNum\":145,\"createDate\":1577011801000,\"id\":308,\"seq\":0,\"spent\":0,\"spentTxId\":\"119\",\"status\":0,\"txId\":\"b247e11d8a201a06f384c6e49d772f7e258240b865a6ca645048f1b96b43e17e\",\"updateDate\":1577114634000}],\"withdraw\":[{\"address\":\"tb1q02cnx308gs7t68mzpz5ad2nrx327v0frayyxuj6xm7x2nedu54ssuc8v9g\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577114750000,\"currency\":1,\"fee\":0E-8,\"id\":109,\"status\":0,\"txId\":\"144a210e-21f2-4305-2665-8505575fabfd15312072722ff\",\"updateDate\":19785600000,\"userId\":1,\"withdrawId\":\"144a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-23 23:26:10', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (121, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qn5w4kf3z60pha7ttsmww58dk4u7ftujq6zgfhmqqav4fxekfdufq3tngr9\",\"balance\":0E-8,\"biz\":0,\"createDate\":1576990225000,\"currency\":\"btc\",\"id\":1,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":0}],\"feePerKb\":3,\"changeAddress\":\"tb1q0c9w9fwfqtm28upqswj49pn8wpj606kkmnfm6pvwptw8ja8tqwas3le9x3\",\"utxos\":[{\"address\":\"tb1qn5w4kf3z60pha7ttsmww58dk4u7ftujq6zgfhmqqav4fxekfdufq3tngr9\",\"balance\":0.00022000,\"biz\":0,\"blockHeight\":1633745,\"confirmNum\":145,\"createDate\":1577011801000,\"id\":308,\"seq\":0,\"spent\":0,\"spentTxId\":\"120\",\"status\":0,\"txId\":\"b247e11d8a201a06f384c6e49d772f7e258240b865a6ca645048f1b96b43e17e\",\"updateDate\":1577160154000}],\"withdraw\":[{\"address\":\"tb1q02cnx308gs7t68mzpz5ad2nrx327v0frayyxuj6xm7x2nedu54ssuc8v9g\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577114750000,\"currency\":1,\"fee\":0E-8,\"id\":109,\"status\":0,\"txId\":\"120\",\"updateDate\":1577160176000,\"userId\":1,\"withdrawId\":\"144a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 14:04:44', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (122, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577171221000,\"currency\":\"btc\",\"id\":269,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"tb1q4vgwqzpmev3c7u2wtju40l394wey6s7fp606p3ejpuj3mnk7f0lsy5mstv\",\"utxos\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0.00022000,\"biz\":1,\"blockHeight\":1635018,\"confirmNum\":1,\"createDate\":1577171594000,\"id\":309,\"seq\":1,\"spent\":0,\"spentTxId\":\"unspent\",\"status\":0,\"txId\":\"03c436eb0d6a45b78fb271cf8cbba6e1d214f66d164135055a00ea95fc2cbd8b\",\"updateDate\":1577171594000}],\"withdraw\":[{\"address\":\"tb1q6fncwf8c3nwqphr6cm9f4fe2nh6y3525cqvdw3\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577171943000,\"currency\":1,\"fee\":0E-8,\"id\":110,\"status\":0,\"txId\":\"224a210e-21f2-4305-2665-8505575fabfd15312072722ff\",\"updateDate\":19785600000,\"userId\":1,\"withdrawId\":\"224a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 15:19:14', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (123, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577171221000,\"currency\":\"btc\",\"id\":269,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"tb1q3gksncjpta9y3pzprd554rwh2ngdek8gw6mk2pkm9rxsp4rajxlqmhmxam\",\"utxos\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0.00022000,\"biz\":1,\"blockHeight\":1635018,\"confirmNum\":1,\"createDate\":1577171594000,\"id\":309,\"seq\":1,\"spent\":0,\"spentTxId\":\"122\",\"status\":0,\"txId\":\"03c436eb0d6a45b78fb271cf8cbba6e1d214f66d164135055a00ea95fc2cbd8b\",\"updateDate\":1577174055000}],\"withdraw\":[{\"address\":\"tb1q6fncwf8c3nwqphr6cm9f4fe2nh6y3525cqvdw3\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577171943000,\"currency\":1,\"fee\":0E-8,\"id\":110,\"status\":0,\"txId\":\"122\",\"updateDate\":1577174063000,\"userId\":1,\"withdrawId\":\"224a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 15:54:43', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (124, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577171221000,\"currency\":\"btc\",\"id\":269,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"tb1qm79srexjhezpdju3r5slx0wxd3276cyt58fzujzsns5pky7nkcrqt90w37\",\"utxos\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0.00022000,\"biz\":1,\"blockHeight\":1635018,\"confirmNum\":1,\"createDate\":1577171594000,\"id\":309,\"seq\":1,\"spent\":0,\"spentTxId\":\"123\",\"status\":0,\"txId\":\"03c436eb0d6a45b78fb271cf8cbba6e1d214f66d164135055a00ea95fc2cbd8b\",\"updateDate\":1577174250000}],\"withdraw\":[{\"address\":\"tb1q6fncwf8c3nwqphr6cm9f4fe2nh6y3525cqvdw3\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577171943000,\"currency\":1,\"fee\":0E-8,\"id\":110,\"status\":0,\"txId\":\"123\",\"updateDate\":1577174252000,\"userId\":1,\"withdrawId\":\"224a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 15:57:43', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (125, 'singing', 0.00022000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577171221000,\"currency\":\"btc\",\"id\":269,\"index\":0,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"tb1qghet55jslz2dzf8tptnl3zfz78xaw4dkhu4gp5r5upch5xttgv8sj3gf6c\",\"utxos\":[{\"address\":\"tb1qjmqqv568qns0x3rhdz6f6lpw3ns7ln7hsgexw3wpmef3q22uqxrs2fh0s6\",\"balance\":0.00022000,\"biz\":1,\"blockHeight\":1635018,\"confirmNum\":1,\"createDate\":1577171594000,\"id\":309,\"seq\":1,\"spent\":0,\"spentTxId\":\"124\",\"status\":0,\"txId\":\"03c436eb0d6a45b78fb271cf8cbba6e1d214f66d164135055a00ea95fc2cbd8b\",\"updateDate\":1577175588000}],\"withdraw\":[{\"address\":\"tb1q6fncwf8c3nwqphr6cm9f4fe2nh6y3525cqvdw3\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577171943000,\"currency\":1,\"fee\":0E-8,\"id\":110,\"status\":0,\"txId\":\"124\",\"updateDate\":1577175592000,\"userId\":1,\"withdrawId\":\"224a210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 16:20:13', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (126, 'singing', 0.00010000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577193260000,\"currency\":\"btc\",\"id\":274,\"index\":1,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"2N6s5s8RHvEF4tcsd3AEXPyLe1KRzqZu6tM\",\"utxos\":[{\"address\":\"2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys\",\"balance\":0.00010000,\"biz\":1,\"blockHeight\":1635056,\"confirmNum\":2,\"createDate\":1577193394000,\"id\":310,\"seq\":1,\"spent\":0,\"spentTxId\":\"unspent\",\"status\":0,\"txId\":\"497f7e082c1f4190b0ecad1f47ac0216b7a8b5b8ffebee352ab9c0102e48f173\",\"updateDate\":1577193433000}],\"withdraw\":[{\"address\":\"2MsKXJEcPhqooNV13yVvzjKvMHBbyKYGJQx\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577193611000,\"currency\":1,\"fee\":0E-8,\"id\":111,\"status\":0,\"txId\":\"224r210e-21f2-4305-2665-8505575fabfd15312072722ff\",\"updateDate\":19785600000,\"userId\":1,\"withdrawId\":\"224r210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 21:20:14', '1970-08-18 08:00:00');
INSERT INTO `btc_withdraw_transaction` VALUES (127, 'singing', 0.00010000, '{\"totalAmount\":\"0.00001000\",\"addresses\":[{\"address\":\"2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys\",\"balance\":0E-8,\"biz\":1,\"createDate\":1577193260000,\"currency\":\"btc\",\"id\":274,\"index\":1,\"nonce\":0,\"status\":0,\"updateDate\":19785600000,\"userId\":1}],\"feePerKb\":3,\"changeAddress\":\"2MyerbC1UfmrYciuGo22uCA59BJHkbc952K\",\"utxos\":[{\"address\":\"2NECfBXuB91nukyYaBeHbkD5rBqMiW5M8Ys\",\"balance\":0.00010000,\"biz\":1,\"blockHeight\":1635056,\"confirmNum\":2,\"createDate\":1577193394000,\"id\":310,\"seq\":1,\"spent\":0,\"spentTxId\":\"126\",\"status\":0,\"txId\":\"497f7e082c1f4190b0ecad1f47ac0216b7a8b5b8ffebee352ab9c0102e48f173\",\"updateDate\":1577197923000}],\"withdraw\":[{\"address\":\"2MsKXJEcPhqooNV13yVvzjKvMHBbyKYGJQx\",\"balance\":0.00001000,\"biz\":1,\"createDate\":1577193611000,\"currency\":1,\"fee\":0E-8,\"id\":111,\"status\":0,\"txId\":\"126\",\"updateDate\":1577197930000,\"userId\":1,\"withdrawId\":\"224r210e-21f2-4305-2665-8505575fabfd15312072722ff\"}]}', 1, 1, '2019-12-24 23:11:31', '1970-08-18 08:00:00');
COMMIT;

-- ----------------------------
-- Table structure for currency_balance
-- ----------------------------
DROP TABLE IF EXISTS `currency_balance`;
CREATE TABLE `currency_balance` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `currency_index` int NOT NULL,
  `balance` decimal(32,9) NOT NULL,
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `currency_index` (`currency_index`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Records of currency_balance
-- ----------------------------
BEGIN;
INSERT INTO `currency_balance` VALUES (17, 1, 0.000100000, '2019-12-22 18:52:01', '2019-12-24 23:22:05');
COMMIT;

-- ----------------------------
-- Table structure for doge_address
-- ----------------------------
DROP TABLE IF EXISTS `doge_address`;
CREATE TABLE `doge_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for doge_utxo_transaction
-- ----------------------------
DROP TABLE IF EXISTS `doge_utxo_transaction`;
CREATE TABLE `doge_utxo_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(192) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `seq` smallint unsigned NOT NULL COMMENT 'output序号',
  `spent` tinyint unsigned NOT NULL COMMENT '是否被花费',
  `status` tinyint DEFAULT '0' COMMENT '-1: 删除；0:提现中;1:签名中;2:已发送; 3:已确认',
  `spent_tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '花费此输出的txid',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`,`seq`) USING BTREE,
  KEY `spent_tx_id` (`spent_tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for doge_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `doge_withdraw_record`;
CREATE TABLE `doge_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(32,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for doge_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `doge_withdraw_transaction`;
CREATE TABLE `doge_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL,
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for etc_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `etc_account_transaction`;
CREATE TABLE `etc_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for etc_address
-- ----------------------------
DROP TABLE IF EXISTS `etc_address`;
CREATE TABLE `etc_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=56 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for etc_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `etc_withdraw_record`;
CREATE TABLE `etc_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for etc_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `etc_withdraw_transaction`;
CREATE TABLE `etc_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for eth_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `eth_account_transaction`;
CREATE TABLE `eth_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2254 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for eth_address
-- ----------------------------
DROP TABLE IF EXISTS `eth_address`;
CREATE TABLE `eth_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=600 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for eth_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `eth_withdraw_record`;
CREATE TABLE `eth_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=263 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for eth_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `eth_withdraw_transaction`;
CREATE TABLE `eth_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=955 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for fc_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `gusd_account_transaction`;
CREATE TABLE `gusd_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for gusd_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `gusd_withdraw_record`;
CREATE TABLE `gusd_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for gusd_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `gusd_withdraw_transaction`;
CREATE TABLE `gusd_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for hur_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `ltc_address`;
CREATE TABLE `ltc_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=54 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ltc_utxo_transaction
-- ----------------------------
DROP TABLE IF EXISTS `ltc_utxo_transaction`;
CREATE TABLE `ltc_utxo_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `seq` smallint unsigned NOT NULL COMMENT 'output序号',
  `spent` tinyint unsigned NOT NULL COMMENT '是否被花费',
  `status` tinyint DEFAULT '0' COMMENT '-1: 删除；0:提现中;1:签名中;2:已发送; 3:已确认',
  `spent_tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '花费此输出的txid',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`,`seq`) USING BTREE,
  KEY `spent_tx_id` (`spent_tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ltc_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `ltc_withdraw_record`;
CREATE TABLE `ltc_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ltc_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `ltc_withdraw_transaction`;
CREATE TABLE `ltc_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for luckywin_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `lym_account_transaction`;
CREATE TABLE `lym_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for lym_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `lym_withdraw_record`;
CREATE TABLE `lym_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for lym_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `lym_withdraw_transaction`;
CREATE TABLE `lym_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for mds_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `mkr_account_transaction`;
CREATE TABLE `mkr_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for mkr_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `mkr_withdraw_record`;
CREATE TABLE `mkr_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for mkr_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `mkr_withdraw_transaction`;
CREATE TABLE `mkr_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for mtx_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `omg_account_transaction`;
CREATE TABLE `omg_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for omg_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `omg_withdraw_record`;
CREATE TABLE `omg_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for omg_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `omg_withdraw_transaction`;
CREATE TABLE `omg_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ong_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `pax_account_transaction`;
CREATE TABLE `pax_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for pax_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `pax_withdraw_record`;
CREATE TABLE `pax_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for pax_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `pax_withdraw_transaction`;
CREATE TABLE `pax_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ppt_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `rbtc_account_transaction`;
CREATE TABLE `rbtc_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rbtc_address
-- ----------------------------
DROP TABLE IF EXISTS `rbtc_address`;
CREATE TABLE `rbtc_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) DEFAULT '0.00000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rbtc_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `rbtc_withdraw_record`;
CREATE TABLE `rbtc_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rbtc_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `rbtc_withdraw_transaction`;
CREATE TABLE `rbtc_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rep_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `rif_account_transaction`;
CREATE TABLE `rif_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rif_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `rif_withdraw_record`;
CREATE TABLE `rif_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rif_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `rif_withdraw_transaction`;
CREATE TABLE `rif_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for rrc_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `snt_account_transaction`;
CREATE TABLE `snt_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for snt_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `snt_withdraw_record`;
CREATE TABLE `snt_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for snt_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `snt_withdraw_transaction`;
CREATE TABLE `snt_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for ssc_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `trx_account_transaction`;
CREATE TABLE `trx_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,6) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for trx_address
-- ----------------------------
DROP TABLE IF EXISTS `trx_address`;
CREATE TABLE `trx_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,6) DEFAULT '0.000000',
  `biz` int NOT NULL COMMENT '业务类型',
  `nonce` int DEFAULT '0' COMMENT '账户类型的币发送交易时需要nounce',
  `index` int NOT NULL COMMENT 'userId生成的第几个地址',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `user_id` (`user_id`,`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for trx_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `trx_withdraw_record`;
CREATE TABLE `trx_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,6) NOT NULL,
  `fee` decimal(16,6) DEFAULT '0.000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for trx_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `trx_withdraw_transaction`;
CREATE TABLE `trx_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,6) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 08:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for tusd_account_transaction
-- ----------------------------
DROP TABLE IF EXISTS `usdc_account_transaction`;
CREATE TABLE `usdc_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for usdc_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `usdc_withdraw_record`;
CREATE TABLE `usdc_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for usdc_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `usdc_withdraw_transaction`;
CREATE TABLE `usdc_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for usdt_address
-- ----------------------------
DROP TABLE IF EXISTS `zrx_account_transaction`;
CREATE TABLE `zrx_account_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `block_height` bigint unsigned NOT NULL,
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) unsigned NOT NULL,
  `confirm_num` bigint unsigned NOT NULL COMMENT '确认数',
  `biz` int NOT NULL COMMENT '业务类型',
  `status` tinyint DEFAULT '0',
  `create_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `tx_id` (`tx_id`) USING BTREE,
  KEY `address` (`address`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for zrx_withdraw_record
-- ----------------------------
DROP TABLE IF EXISTS `zrx_withdraw_record`;
CREATE TABLE `zrx_withdraw_record` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'waiting',
  `address` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `balance` decimal(32,8) NOT NULL,
  `fee` decimal(16,8) DEFAULT '0.00000000',
  `currency` int NOT NULL,
  `biz` int NOT NULL,
  `withdraw_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '系统间交互的唯一标识，防止发送重复交易',
  `status` tinyint DEFAULT '0' COMMENT '0:提现中;1:签名中;2:已发送; 3:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `withdraw_id` (`withdraw_id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for zrx_withdraw_transaction
-- ----------------------------
DROP TABLE IF EXISTS `zrx_withdraw_transaction`;
CREATE TABLE `zrx_withdraw_transaction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `tx_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `balance` decimal(32,8) NOT NULL COMMENT '此笔交易的金额',
  `signature` text CHARACTER SET utf8 COLLATE utf8_general_ci,
  `currency` int NOT NULL,
  `status` smallint NOT NULL COMMENT '0:正在签名;1:已发送;2:已确认',
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` timestamp NOT NULL DEFAULT '1970-08-18 16:00:00' ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `tx_id` (`tx_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

-- ----------------------------
-- Table structure for zxt_account_transaction
-- ----------------------------
