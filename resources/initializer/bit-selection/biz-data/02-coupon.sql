-- 券模板 7 张，按授权经销商真实会发的形态设计
--
-- 有效期一律相对 now() 现算，语料不会因为放了三个月就整批过期
--
-- 三处刻意留下的坑，用来分开「能用的券」和「看着能用的券」：
-- EDU-PAD-920 限平板，买手机时金额再大也用不了；
-- PHONE-8000-500 限手机且门槛 8000，iPhone 17 单买 6799 够不到；
-- NEW-1000-100 已经过期，只按金额筛会把它推荐出去
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_coupon (id, coupon_code, name, type, threshold, discount_value, category,
                      valid_from, valid_to, create_time, update_time)
VALUES (1, 'EDU-MAC-920', '教育优惠·Mac', '折扣', 0.00, 0.92, '电脑', now() - INTERVAL '180 days', now() + INTERVAL '185 days', now(), now()),
       (2, 'EDU-PAD-920', '教育优惠·iPad', '折扣', 0.00, 0.92, '平板', now() - INTERVAL '180 days', now() + INTERVAL '185 days', now(), now()),
       (3, 'SHOP-5000-300', '店铺满减券', '满减', 5000.00, 300.00, NULL, now() - INTERVAL '30 days', now() + INTERVAL '60 days', now(), now()),
       (4, 'SHOP-12000-800', '大额满减券', '满减', 12000.00, 800.00, NULL, now() - INTERVAL '30 days', now() + INTERVAL '60 days', now(), now()),
       (5, 'ACC-500-50', '配件专享券', '满减', 500.00, 50.00, '配件', now() - INTERVAL '30 days', now() + INTERVAL '60 days', now(), now()),
       (6, 'PHONE-8000-500', '手机专享券', '满减', 8000.00, 500.00, '手机', now() - INTERVAL '30 days', now() + INTERVAL '60 days', now(), now()),
       (7, 'NEW-1000-100', '新客立减券', '满减', 1000.00, 100.00, NULL, now() - INTERVAL '120 days', now() - INTERVAL '5 days', now(), now());
