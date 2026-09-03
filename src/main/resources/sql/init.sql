-- ============================================================
-- 热点数据缓存预热与缓存一致性保障系统 - 数据库初始化脚本
-- 培训结课设计
-- ============================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `cache_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `cache_db`;

-- ============================================================
-- 1. 商品分类表
-- ============================================================
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
    `id`    BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '分类ID',
    `name`  VARCHAR(50) NOT NULL                 COMMENT '分类名称',
    `sort`  INT          DEFAULT 0               COMMENT '排序号',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- ============================================================
-- 2. 商品表（核心业务表，演示热点数据缓存）
-- ============================================================
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
    `id`          BIGINT         NOT NULL AUTO_INCREMENT  COMMENT '商品ID',
    `name`        VARCHAR(100)   NOT NULL                 COMMENT '商品名称',
    `category_id` BIGINT         NOT NULL                 COMMENT '分类ID',
    `price`       DECIMAL(10,2)  NOT NULL                 COMMENT '商品价格',
    `stock`       INT            NOT NULL DEFAULT 0       COMMENT '库存数量',
    `description` VARCHAR(500)   DEFAULT ''               COMMENT '商品描述',
    `status`      TINYINT        DEFAULT 1                COMMENT '状态：1=上架 0=下架',
    `view_count`  BIGINT         DEFAULT 0                COMMENT '浏览次数（模拟热点数据）',
    `create_time` DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ============================================================
-- 3. 插入分类测试数据
-- ============================================================
INSERT INTO `category` (`id`, `name`, `sort`) VALUES
(1, '手机数码', 1),
(2, '电脑办公', 2),
(3, '家用电器', 3),
(4, '服饰鞋包', 4),
(5, '食品生鲜', 5);

-- ============================================================
-- 4. 插入商品测试数据（20条，模拟热点数据场景）
-- ============================================================
INSERT INTO `product` (`id`, `name`, `category_id`, `price`, `stock`, `description`, `status`, `view_count`) VALUES
(1,  'iPhone 15 Pro Max 256G',     1, 9999.00, 500,  '苹果旗舰手机，A17 Pro芯片，钛金属边框', 1, 99985),
(2,  '华为 Mate 60 Pro',           1, 6999.00, 800,  '麒麟9000S芯片，卫星通话，昆仑玻璃',     1, 88742),
(3,  '小米14 Ultra',               1, 6499.00, 600,  '骁龙8 Gen3，徕卡光学，2K超视屏',       1, 75210),
(4,  'OPPO Find X7 Ultra',         1, 5999.00, 400,  '双潜望长焦，哈苏影像，5400mAh',        1, 53108),
(5,  'vivo X100 Pro',              1, 4999.00, 700,  '天玑9300，蔡司APO长焦，蓝心大模型',     1, 48890),
(6,  'MacBook Pro M3 14寸',        2, 14999.00, 200, 'M3 Pro芯片，Liquid视网膜XDR',          1, 42100),
(7,  '联想 ThinkPad X1 Carbon',    2, 12999.00, 150, 'i7-1365U，32G内存，1TB SSD',           1, 32100),
(8,  '戴尔 XPS 15',                2, 10999.00, 180, 'i9-13900H，RTX 4060，4K OLED',         1, 28800),
(9,  '华硕 ROG 幻14',             2, 9999.00, 120,  'Ryzen 9，RTX 4070，14寸OLED',           1, 25600),
(10, '华为 MateBook X Pro',        2, 8999.00, 250,  'i7-1360P，3.1K触控屏，超级终端',        1, 23400),
(11, '海尔冰箱 BCD-560',          3, 4999.00, 300,  '560L风冷无霜，变频压缩机',              1, 15600),
(12, '美的空调 KFR-35GW',         3, 2999.00, 500,  '1.5匹变频，新一级能效，自清洁',          1, 14300),
(13, '格力空调 KFR-72LW',         3, 5999.00, 200,  '3匹立柜式，新一级能效，除霜功能',        1, 12100),
(14, '小米电视 75英寸',           3, 3999.00, 400,  '4K超高清，120Hz高刷，远场语音',          1, 10900),
(15, '索尼电视 65A95L',           3, 19999.00, 50,  'OLED 4K，XR芯片，杜比视界',              1, 8700),
(16, '耐克 Air Force 1',          4, 799.00, 1000,  '经典小白鞋，皮质鞋面，气垫缓震',         1, 6800),
(17, '阿迪达斯 Ultraboost',       4, 1299.00, 600,  'BOOST中底，Primeknit鞋面',             1, 5900),
(18, '三只松鼠坚果礼盒',          5, 168.00, 2000,  '每日坚果30袋，混合装',                  1, 3200),
(19, '百草味肉松饼',              5, 39.90, 3000,  '早餐零食，整箱装',                      1, 2100),
(20, '伊利纯牛奶 250ml*24',       5, 59.90, 5000,  '整箱装，原生高钙',                      1, 1500);
