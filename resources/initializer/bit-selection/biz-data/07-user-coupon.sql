-- 持券关系 7 条
--
-- 状态只说有没有被用掉，过没过期一律按当前时间现判：NEW-1000-100 在券表里已过期，
-- 这里仍是未使用，「我有哪些券能用」要能把它筛掉才算对
--
-- EDU-MAC-920 已核销到 88233，取消那一单时要能原样退回
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_user_coupon (id, user_id, coupon_code, status, order_no, create_time, update_time, use_time)
VALUES (1, ${USER_ID}, 'EDU-PAD-920', '未使用', NULL, now(), now(), NULL),
       (2, ${USER_ID}, 'SHOP-5000-300', '未使用', NULL, now(), now(), NULL),
       (3, ${USER_ID}, 'SHOP-12000-800', '未使用', NULL, now(), now(), NULL),
       (4, ${USER_ID}, 'ACC-500-50', '未使用', NULL, now(), now(), NULL),
       (5, ${USER_ID}, 'PHONE-8000-500', '未使用', NULL, now(), now(), NULL),
       (6, ${USER_ID}, 'NEW-1000-100', '未使用', NULL, now(), now(), NULL),
       (7, ${USER_ID}, 'EDU-MAC-920', '已使用', '88233', now(), now(), now());
