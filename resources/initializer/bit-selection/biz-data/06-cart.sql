-- 购物车 5 件
--
-- added_price 是加购当时的价格，与商品现价现比就得出「降价了多少」，因此不另建价格历史表：
-- iPhone 18 Pro 加购价高于现价，是车里唯一降过价的一件
--
-- AirPods Pro 3 现在零库存、Vision Pro 只剩 3 件，
-- 「购物车这些帮我下单」必须先撞上这两件才有得答，全是有货的车走不到这一步
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_cart (id, user_id, sku_code, quantity, added_price, create_time, update_time)
VALUES (1, ${USER_ID}, 'MJT74CH/A', 1, 11198.88, now() - INTERVAL '16 days', now()),
       (2, ${USER_ID}, 'MFHP4CH/A', 1, 1899.00, now() - INTERVAL '6 days', now()),
       (3, ${USER_ID}, 'MFHE4CH/A', 1, 31999.00, now() - INTERVAL '4 days', now()),
       (4, ${USER_ID}, 'MK8T4FE/A', 2, 399.00, now() - INTERVAL '2 days', now()),
       (5, ${USER_ID}, 'MDF44FE/A', 1, 379.00, now() - INTERVAL '5 hours', now());
