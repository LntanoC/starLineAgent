-- 物流轨迹 18 条，覆盖 5 个运单
--
-- 已签收的单都以签收节点收尾，签收时间与订单表的 receive_time 对齐，两边对不上会被当成数据错
--
-- 88234 还在途中，只有揽收与发出两个节点，「快递到哪了」问的就是这一类
-- 建表脚本不给任何列默认值，批量灌数据这条路又不经过 MyBatis，
-- 所以 id 与 create_time / update_time 都要在 SQL 里自己给

INSERT INTO t_logistics_trace (id, tracking_no, trace_time, location, description, create_time, update_time)
VALUES (1, 'BIT9100000231', now() - INTERVAL '9 days', '上海分拣中心', '包裹已揽收', now(), now()),
       (2, 'BIT9100000231', now() - INTERVAL '8 days', '上海转运中心', '快件已发出', now(), now()),
       (3, 'BIT9100000231', now() - INTERVAL '4 days', '浦东新区张江营业点', '快件已到达派送点', now(), now()),
       (4, 'BIT9100000231', now() - INTERVAL '3 days', '浦东新区张江营业点', '已签收，签收人：本人', now(), now()),
       (5, 'BIT9100000232', now() - INTERVAL '19 days', '上海分拣中心', '包裹已揽收', now(), now()),
       (6, 'BIT9100000232', now() - INTERVAL '18 days', '上海转运中心', '快件已发出', now(), now()),
       (7, 'BIT9100000232', now() - INTERVAL '15 days', '浦东新区张江营业点', '快件已到达派送点', now(), now()),
       (8, 'BIT9100000232', now() - INTERVAL '14 days', '浦东新区张江营业点', '已签收，签收人：本人', now(), now()),
       (9, 'BIT9100000233', now() - INTERVAL '15 days', '上海分拣中心', '包裹已揽收', now(), now()),
       (10, 'BIT9100000233', now() - INTERVAL '14 days', '上海转运中心', '快件已发出', now(), now()),
       (11, 'BIT9100000233', now() - INTERVAL '11 days', '浦东新区张江营业点', '快件已到达派送点', now(), now()),
       (12, 'BIT9100000233', now() - INTERVAL '10 days', '浦东新区张江营业点', '已签收，签收人：本人', now(), now()),
       (13, 'BIT9100000234', now() - INTERVAL '2 days', '上海分拣中心', '包裹已揽收', now(), now()),
       (14, 'BIT9100000234', now() - INTERVAL '1 days', '上海转运中心', '快件已发出', now(), now()),
       (15, 'BIT9100000238', now() - INTERVAL '29 days', '上海分拣中心', '包裹已揽收', now(), now()),
       (16, 'BIT9100000238', now() - INTERVAL '28 days', '上海转运中心', '快件已发出', now(), now()),
       (17, 'BIT9100000238', now() - INTERVAL '26 days', '浦东新区张江营业点', '快件已到达派送点', now(), now()),
       (18, 'BIT9100000238', now() - INTERVAL '25 days', '浦东新区张江营业点', '已签收，签收人：本人', now(), now());
