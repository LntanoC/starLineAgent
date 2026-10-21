-- 售后单 2 张
--
-- 售后不回写订单状态：88232 在退货处理中，订单本身仍是已签收。
-- 一单多件时只退其中一件，把整单履约状态覆盖成退款中是错的
--
-- 一张处理中、一张已完成，「我的退款到哪了」才有两种答案可给
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_after_sale (id, after_sale_no, order_no, sku_code, type, reason, status,
                          create_time, update_time, finish_time)
VALUES (1, 'AS88232001', '88232', 'MFHP4CH/A', '退货退款', '左耳无声音，已按说明书重置仍无改善', '处理中', now() - INTERVAL '13 days', now(), NULL),
       (2, 'AS88231001', '88231', 'MG6W4CH/A', '换货', '屏幕左上角有一处亮点', '已完成', now() - INTERVAL '2 days', now(), now() - INTERVAL '1 days');
