-- 比特严选业务库 ragent_bit 的数据清空脚本
--
-- 只清数据不动结构：表归 mcp-server 启动期建，这里 DROP 一张表，下次启动因为 CREATE IF NOT EXISTS
-- 会静默补回一张空表，而版本号还停在原处——错误会以「某个字段不存在」的形式出现在离原因很远的地方
--
-- 固定白名单，不加 CASCADE：这个库里本来就没有外键，写 CASCADE 等于给以后加进来的关系开一张空白支票

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

TRUNCATE TABLE
    t_after_sale,
    t_logistics_trace,
    t_order_item,
    t_order,
    t_user_coupon,
    t_coupon,
    t_cart,
    t_ticket,
    t_product_sku,
    t_product
    RESTART IDENTITY;
