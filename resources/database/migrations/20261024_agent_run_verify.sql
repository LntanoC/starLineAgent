BEGIN;
SET LOCAL lock_timeout='10s';
INSERT INTO t_agent_run(id,run_id,conversation_id,user_id) VALUES ('-800000000000000001','__audit_verify__','__verify__','__verify__');
INSERT INTO t_agent_run_node(id,run_id,node_id,node_type,status) VALUES ('-800000000000000002','__audit_verify__','root','AGENT','SUCCESS');
DO $$ BEGIN
 BEGIN
  INSERT INTO t_agent_run(id,run_id,conversation_id,user_id) VALUES ('-800000000000000003','__audit_verify__','__verify__','__verify__');
  RAISE EXCEPTION 'run uniqueness did not hold';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO t_agent_run_node(id,run_id,node_id,node_type,status) VALUES ('-800000000000000004','__audit_verify__','root','AGENT','SUCCESS');
  RAISE EXCEPTION 'node uniqueness did not hold';
 EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
ROLLBACK;
SELECT count(*) AS synthetic_runs_remaining FROM t_agent_run WHERE run_id='__audit_verify__';
SELECT count(*) AS synthetic_nodes_remaining FROM t_agent_run_node WHERE run_id='__audit_verify__';
