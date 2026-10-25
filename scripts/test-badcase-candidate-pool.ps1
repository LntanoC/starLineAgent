param([string]$ProjectRoot='C:\Users\cjx\Desktop\starLineAgent',[string]$Goal='test')
$ErrorActionPreference='Stop'
$taskLog=Join-Path $ProjectRoot 'docs/server-operations/2026-10-25.md'
Add-Content -LiteralPath $taskLog -Value "`n### 隔离测试执行 $(Get-Date -Format s)（执行前登记）`n执行 scripts/test-badcase-candidate-pool.ps1：SSH 建立 25433:127.0.0.1:5432 隧道；执行 sudo -n docker exec starline-api printenv SPRING_DATASOURCE_PASSWORD，凭据仅在内存；Maven 对 bc_pool_it_* 与 bc_runtime_it_* 隔离 schema 执行合成数据和迁移测试，finally 删除。公共业务表不变。`n"
$taskSshArgs=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','ExitOnForwardFailure=yes','-o','StrictHostKeyChecking=yes','-i',(Join-Path $env:USERPROFILE '.ssh\id_ed25519'),'-N','-L','127.0.0.1:25433:127.0.0.1:5432','ubuntu@139.199.191.109')
$taskTunnel=Start-Process -FilePath 'C:\Windows\System32\OpenSSH\ssh.exe' -ArgumentList $taskSshArgs -WindowStyle Hidden -PassThru
try {
 $taskPassword=(& ssh -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -i (Join-Path $env:USERPROFILE '.ssh\id_ed25519') ubuntu@139.199.191.109 'sudo -n docker exec starline-api printenv SPRING_DATASOURCE_PASSWORD' | Out-String).Trim()
 if($LASTEXITCODE -ne 0 -or [string]::IsNullOrEmpty($taskPassword)){throw 'Database test credential unavailable'}
 $env:STARLINE_BADCASE_TEST_URL='jdbc:postgresql://127.0.0.1:25433/starline';$env:STARLINE_BADCASE_TEST_USER='starline';$env:STARLINE_BADCASE_TEST_PASSWORD=$taskPassword
 $taskMaven=Get-ChildItem (Join-Path $env:USERPROFILE '.m2\wrapper\dists\apache-maven-3.9.11') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
 Push-Location $ProjectRoot
 try {& $taskMaven -q -pl bootstrap -am '-Dtest=*BadCase*,ConversationSignalTest' '-Dsurefire.failIfNoSpecifiedTests=false' $Goal *> (Join-Path $env:TEMP 'starline-pool-tests.log');$taskTestExit=$LASTEXITCODE} finally {Pop-Location}
 Get-Content (Join-Path $env:TEMP 'starline-pool-tests.log') -Tail 60
 Add-Content -LiteralPath $taskLog -Value "隔离测试结果：Maven exit=$taskTestExit；输出位于本地临时目录 starline-pool-tests.log，不包含凭据。隧道与测试进程环境变量将清理。"
 exit $taskTestExit
} finally {
 Remove-Item Env:STARLINE_BADCASE_TEST_PASSWORD,Env:STARLINE_BADCASE_TEST_URL,Env:STARLINE_BADCASE_TEST_USER -ErrorAction SilentlyContinue
 $taskPassword=$null
 if($taskTunnel -and -not $taskTunnel.HasExited){Stop-Process -Id $taskTunnel.Id}
}
