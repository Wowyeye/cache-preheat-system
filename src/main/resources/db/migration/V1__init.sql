-- ============================================================
-- V1__init.sql - 全部表结构（首次部署执行）
-- v3 工程化改造：Flyway 版本化管理，改表结构只需新增 V2/V3 脚本
-- 注意：Flyway 在应用启动时按版本号顺序执行，本脚本不建库
--       （库名由 JDBC 连接串 MYSQL_DATABASE 决定）
-- ============================================================

-- ============================================================
-- 1. 商品分类表
-- ============================================================
CREATE TABLE IF NOT EXISTS `category` (
    `id`    BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '分类ID',
    `name`  VARCHAR(50) NOT NULL                 COMMENT '分类名称',
    `sort`  INT          DEFAULT 0               COMMENT '排序号',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- ============================================================
-- 2. 商品表（核心业务表，演示热点数据缓存）
-- ============================================================
CREATE TABLE IF NOT EXISTS `product` (
    `id`          BIGINT         NOT NULL AUTO_INCREMENT  COMMENT '商品ID',
    `name`        VARCHAR(100)   NOT NULL                 COMMENT '商品名称',
    `category_id` BIGINT         NOT NULL                 COMMENT '分类ID',
    `price`       DECIMAL(10,2)  NOT NULL                 COMMENT '商品价格',
    `stock`       INT            NOT NULL DEFAULT 0       COMMENT '库存数量',
    `description` VARCHAR(500)   DEFAULT ''               COMMENT '商品描述',
    `status`      TINYINT        DEFAULT 1               COMMENT '状态：1=上架 0=下架',
    `view_count`  BIGINT         DEFAULT 0               COMMENT '浏览次数（模拟热点数据）',
    `create_time` DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ============================================================
-- 3. 用户表（注册/登录，区分管理员与普通用户）
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL                 COMMENT '用户名（登录账号）',
    `password`    VARCHAR(100) NOT NULL                 COMMENT '密码（BCrypt加密存储）',
    `nickname`    VARCHAR(50)  DEFAULT ''               COMMENT '昵称（展示用）',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'USER'  COMMENT '角色：ADMIN=管理员 USER=普通用户',
    `status`      TINYINT      NOT NULL DEFAULT 1       COMMENT '状态：1=正常 0=禁用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 4. 订单表（购买流程 + 8 态状态机）
--    PENDING_PAYMENT待支付 / PAID已支付 / COMPLETED已完成 / CANCELLED已取消
--    REFUNDING退款中 / REFUNDED已退款 / RETURNING退货中 / RETURNED已退货
-- ============================================================
CREATE TABLE IF NOT EXISTS `orders` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`     VARCHAR(32)   NOT NULL                COMMENT '订单号（业务唯一）',
    `user_id`      BIGINT        NOT NULL                COMMENT '下单用户ID',
    `total_amount` DECIMAL(10,2) NOT NULL                COMMENT '订单总金额',
    `status`       VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '订单状态',
    `remark`       VARCHAR(200)  DEFAULT ''              COMMENT '订单备注（退款/退货原因）',
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `pay_time`     DATETIME      NULL                    COMMENT '支付时间',
    `finish_time`  DATETIME      NULL                    COMMENT '完结时间（完成/取消/退款/退货）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_status` (`status`),
    -- 【v3】超时取消扫描走 status + create_time 组合，避免全表扫
    INDEX `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ============================================================
-- 5. 订单明细表（商品快照：下单时冻结名称/价格，后续改价不影响历史订单）
-- ============================================================
CREATE TABLE IF NOT EXISTS `order_item` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `order_id`      BIGINT        NOT NULL                COMMENT '订单ID',
    `product_id`    BIGINT        NOT NULL                COMMENT '商品ID',
    `product_name`  VARCHAR(100)  NOT NULL                COMMENT '商品名称快照（下单时冻结）',
    `product_price` DECIMAL(10,2) NOT NULL                COMMENT '成交单价快照（下单时冻结）',
    `quantity`      INT           NOT NULL DEFAULT 1      COMMENT '购买数量',
    `subtotal`      DECIMAL(10,2) NOT NULL                COMMENT '小计金额',
    PRIMARY KEY (`id`),
    INDEX `idx_order` (`order_id`),
    INDEX `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';
