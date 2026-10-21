-- 订单 8 单与对应订单行
--
-- ${USER_ID} 由 BizDataInitMain 用平台库里初始化账号的真实 ID 替换，替换值已经过 SQL 字面量转义，
-- 所以这里不带引号。挂错人的后果不是报错而是全部演示都回「未找到」——工具一律按登录态圈数据
--
-- 时间一律相对 now() 现算：七天无理由窗口按签收时间现算，写死日期会让语料过几天就全部超窗
--
-- 五种状态各有一单，订单变更的四条分支才演得出来；签收时间刻意拉开三档：
-- 88231 签收 3 天在七天无理由窗口内、88238 签收 25 天两个窗口都已过、
-- 88232 签收 14 天是中间那档（七天无理由已过、十五天质量问题还在）
--
-- 88234 与 88238 是一单两件，退其中一件时整单状态不该跟着变，这条演示要靠它们
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_order (id, order_no, user_id, status, total_amount, discount_amount, pay_amount, coupon_code,
                     receiver_name, receiver_phone, receiver_address, tracking_no,
                     create_time, update_time, pay_time, ship_time, receive_time, cancel_time)
VALUES (1, '88231', ${USER_ID}, '已签收', 6799.00, 0.00, 6799.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', 'BIT9100000231',
        now() - INTERVAL '10 days', now(), now() - INTERVAL '10 days', now() - INTERVAL '9 days', now() - INTERVAL '3 days', NULL),
       (2, '88232', ${USER_ID}, '已签收', 1899.00, 0.00, 1899.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', 'BIT9100000232',
        now() - INTERVAL '20 days', now(), now() - INTERVAL '20 days', now() - INTERVAL '19 days', now() - INTERVAL '14 days', NULL),
       (3, '88233', ${USER_ID}, '已签收', 9999.00, 799.92, 9199.08, 'EDU-MAC-920',
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', 'BIT9100000233',
        now() - INTERVAL '16 days', now(), now() - INTERVAL '16 days', now() - INTERVAL '15 days', now() - INTERVAL '10 days', NULL),
       (4, '88234', ${USER_ID}, '已发货', 6998.00, 0.00, 6998.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', 'BIT9100000234',
        now() - INTERVAL '4 days', now(), now() - INTERVAL '4 days', now() - INTERVAL '2 days', NULL, NULL),
       (5, '88235', ${USER_ID}, '已支付待发货', 3378.00, 0.00, 3378.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', NULL,
        now() - INTERVAL '1 days', now(), now() - INTERVAL '1 days', NULL, NULL, NULL),
       (6, '88236', ${USER_ID}, '待支付', 498.00, 0.00, 498.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', NULL,
        now() - INTERVAL '3 minutes', now(), NULL, NULL, NULL, NULL),
       (7, '88237', ${USER_ID}, '已取消', 999.00, 0.00, 999.00, NULL,
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', NULL,
        now() - INTERVAL '7 days', now(), NULL, NULL, NULL, now() - INTERVAL '7 days'),
       (8, '88238', ${USER_ID}, '已签收', 10398.00, 800.00, 9598.00, 'SHOP-12000-800',
        '张明', '13800138000', '上海市浦东新区张江镇科苑路 88 号 3 号楼 501 室', 'BIT9100000238',
        now() - INTERVAL '30 days', now(), now() - INTERVAL '30 days', now() - INTERVAL '29 days', now() - INTERVAL '25 days', NULL);

INSERT INTO t_order_item (id, order_no, sku_code, sku_name, price, quantity, create_time, update_time)
VALUES (1, '88231', 'MG6W4CH/A', 'iPhone 17 256GB 黑色', 6799.00, 1, now(), now()),
       (2, '88232', 'MFHP4CH/A', 'AirPods Pro 3', 1899.00, 1, now(), now()),
       (3, '88233', 'MDH74CH/A', 'MacBook Air 银色 13 英寸 8核 GPU', 9999.00, 1, now(), now()),
       (4, '88234', 'MH304CH/A', 'iPad Air 11 英寸 (M4) 128GB 深空灰色 无线局域网', 5999.00, 1, now(), now()),
       (5, '88234', 'MX2D3CH/A', 'Apple Pencil Pro', 999.00, 1, now(), now()),
       (6, '88235', 'MJF54CH/B', 'Apple Watch Series 12 深空灰色 42 毫米 铝金属 GPS', 2999.00, 1, now(), now()),
       (7, '88235', 'MDF44FE/A', '42 毫米 Black Unity 回环式运动表带 - 团结之韵', 379.00, 1, now(), now()),
       (8, '88236', 'MFE94CH/A', 'AirTag 单件装', 249.00, 2, now(), now()),
       (9, '88237', 'MHY53CH/A', 'HomePod mini 白色', 999.00, 1, now(), now()),
       (10, '88238', 'MJT74CH/A', 'iPhone 18 Pro 256GB 黑色', 9999.00, 1, now(), now()),
       (11, '88238', 'MK8T4FE/A', 'iPhone 18 Pro 专用 MagSafe 硅胶保护壳 - 洋红色', 399.00, 1, now(), now());
