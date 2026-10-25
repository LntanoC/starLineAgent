param([string]$ProjectRoot='C:\Users\cjx\Desktop\starLineAgent')
$ErrorActionPreference='Stop'
$taskLog=Join-Path $ProjectRoot 'docs/server-operations/2026-10-25.md'
$taskRelease='/home/ubuntu/starline-infra/releases/starline-20261022'
$taskRemoteStage="$taskRelease/.badcase-pool-upload-20261025"
$taskStage=Join-Path $ProjectRoot '.local-backups\badcase-pool-upload-20261025'
$taskJar=Join-Path $ProjectRoot 'bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'
$taskDist=Join-Path $ProjectRoot 'frontend\dist'
if(!(Test-Path $taskJar) -or !(Test-Path "$taskDist\index.html")){throw 'Build artifacts are missing'}
New-Item -ItemType Directory -Force -Path $taskStage | Out-Null
Copy-Item -LiteralPath $taskJar -Destination "$taskStage\starline.jar" -Force
$taskReleaseLocal=Join-Path $ProjectRoot 'resources\docker\server\releases\starline-20261022'
foreach($taskName in @('application.yaml','nginx.conf')){Copy-Item "$taskReleaseLocal\$taskName" "$taskStage\$taskName" -Force}
Copy-Item "$ProjectRoot\resources\database\schema_pg.sql" "$taskStage\schema_pg.sql" -Force
foreach($taskName in @('20261025_bad_case_candidate_pool.sql')){Copy-Item "$ProjectRoot\resources\database\migrations\$taskName" "$taskStage\$taskName" -Force}
foreach($taskName in @('deploy-badcase-candidate-pool.sh','verify-badcase-candidate-pool.py')){Copy-Item "$ProjectRoot\scripts\$taskName" "$taskStage\$taskName" -Force}
& tar -czf "$taskStage\frontend.tar.gz" -C $taskDist .
if($LASTEXITCODE -ne 0){throw 'Frontend archive failed'}
$taskYamlPath=Join-Path $taskStage 'application.yaml'
$taskYaml=[System.IO.File]::ReadAllText($taskYamlPath)
foreach($taskCode in @('agent-runtime','user-correction','starline-business')){$taskYaml=$taskYaml.Replace(($taskCode+": {mode: 'ON'}"),($taskCode+": {mode: 'SHADOW'}"))}
[System.IO.File]::WriteAllText($taskYamlPath,$taskYaml,[System.Text.UTF8Encoding]::new($false))
$taskBash=Join-Path $taskStage 'deploy-badcase-candidate-pool.sh'
[System.IO.File]::WriteAllText($taskBash,([System.IO.File]::ReadAllText($taskBash).Replace("`r`n","`n")),[System.Text.UTF8Encoding]::new($false))
$taskHashes=Get-ChildItem $taskStage -File | Where-Object Name -ne 'SHA256SUMS' | ForEach-Object {"$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)"}
[System.IO.File]::WriteAllText("$taskStage\SHA256SUMS",($taskHashes -join "`n")+"`n",[System.Text.UTF8Encoding]::new($false))
$taskSsh=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-i',(Join-Path $env:USERPROFILE '.ssh\id_ed25519'))
Add-Content -LiteralPath $taskLog -Value "`n## 候选池正式部署 $(Get-Date -Format s)（执行前登记）`n计划创建 $taskRemoteStage 隔离上传目录；SCP 上传本地已构建JAR、前端包、两份增量迁移、初始化快照、application.yaml、nginx.conf和部署/验收脚本及SHA256清单。执行 bash $taskRemoteStage/deploy-badcase-candidate-pool.sh。脚本先备份数据库和发布文件，再迁移重放、灰度联调、开启四类高置信入口并重启API/前端；失败恢复上一版应用。下方保存完整脚本，不含任何真实凭据。`n"
Add-Content -LiteralPath $taskLog -Value ("~~~bash`n"+[System.IO.File]::ReadAllText("$ProjectRoot\scripts\deploy-badcase-candidate-pool.sh")+"`n~~~`n")
& ssh @taskSsh ubuntu@139.199.191.109 "test ! -e $taskRemoteStage && mkdir -p $taskRemoteStage"
if($LASTEXITCODE -ne 0){throw 'Remote staging path already exists or cannot be prepared'}
Add-Content -LiteralPath $taskLog -Value '结果：远端隔离暂存目录已创建。下一步执行上述SCP上传。'
$taskUpload=Get-ChildItem $taskStage -File | Select-Object -ExpandProperty FullName
& scp @taskSsh @taskUpload "ubuntu@139.199.191.109:$taskRemoteStage/"
if($LASTEXITCODE -ne 0){throw 'Upload failed; release has not been changed'}
Add-Content -LiteralPath $taskLog -Value '结果：上传完成，正式发布文件尚未替换。下一步按上方登记执行部署脚本（内部先核对所有SHA256）。'
& ssh @taskSsh ubuntu@139.199.191.109 "bash $taskRemoteStage/deploy-badcase-candidate-pool.sh" 2>&1 | Tee-Object -FilePath (Join-Path $env:TEMP 'starline-pool-deploy.log')
$taskDeployExit=$LASTEXITCODE
Add-Content -LiteralPath $taskLog -Value ("部署脚本 exit=$taskDeployExit。结果：`n~~~text`n"+((Get-Content (Join-Path $env:TEMP 'starline-pool-deploy.log') -Tail 30)-join "`n")+"`n~~~`n")
if($taskDeployExit -ne 0){throw 'Deployment failed; inspect recorded rollback outcome'}
Add-Content -LiteralPath $taskLog -Value "计划同步服务器最终灰度配置：scp ubuntu@139.199.191.109:$taskRelease/application.yaml resources/docker/server/releases/starline-20261022/application.yaml；该文件来自本次已上传的无密钥配置，仅四类入口模式由脚本从SHADOW改为ON。另将已部署的两份SQL和脚本同步到本地服务器快照目录，不复制.env、凭据、备份或运行数据。"
& scp @taskSsh "ubuntu@139.199.191.109:$taskRelease/application.yaml" "$taskReleaseLocal\application.yaml"
if($LASTEXITCODE -ne 0){throw 'Server-to-local config synchronization failed'}
foreach($taskName in @('20261025_bad_case_candidate_pool.sql','deploy-badcase-candidate-pool.sh','verify-badcase-candidate-pool.py')){Copy-Item "$taskStage\$taskName" "$taskReleaseLocal\$taskName" -Force}
Add-Content -LiteralPath $taskLog -Value '结果：生产最终application.yaml与新增迁移/部署脚本已同步本地项目快照，未同步密钥或运行数据。'
Write-Output 'DEPLOY_AND_CONFIG_SYNC_COMPLETE'
