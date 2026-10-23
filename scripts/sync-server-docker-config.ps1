[CmdletBinding()]
param(
    [string]$Server = "ubuntu@139.199.191.109",
    [string]$IdentityFile = (Join-Path $env:USERPROFILE ".ssh/id_ed25519"),
    [string]$RemoteRoot = "/home/ubuntu/starline-infra",
    [string]$Release = "starline-20261022"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDir = Join-Path $repoRoot "resources/docker/server"
$opsLog = Join-Path $repoRoot "docs/server-operations.md"
$changeIndex = Join-Path $repoRoot "docs/change-log.md"
$identityResolved = (Resolve-Path -LiteralPath $IdentityFile).Path
$releaseRoot = $RemoteRoot.TrimEnd("/") + "/releases/" + $Release
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Add-Utf8Text {
    param([string]$Path, [string]$Text)
    [System.IO.File]::AppendAllText($Path, $Text + [Environment]::NewLine, $utf8NoBom)
}

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
$files = @(
    [pscustomobject]@{ Remote = $RemoteRoot.TrimEnd("/") + "/compose.yaml"; Name = "compose.yaml" }
    [pscustomobject]@{ Remote = $releaseRoot + "/pgvector.override.yaml"; Name = "pgvector.override.yaml" }
    [pscustomobject]@{ Remote = $releaseRoot + "/starline-app.compose.yaml"; Name = "starline-app.compose.yaml" }
    [pscustomobject]@{ Remote = $releaseRoot + "/rocketmq-broker.conf"; Name = "rocketmq-broker.conf" }
    [pscustomobject]@{ Remote = $releaseRoot + "/nginx.conf"; Name = "nginx.conf" }
)

$date = Get-Date -Format "yyyy-MM-dd"
$stageDir = Join-Path $targetDir (".sync-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stageDir | Out-Null

$commands = @()
foreach ($file in $files) {
    $source = $Server + ":" + $file.Remote
    $destination = Join-Path $stageDir $file.Name
    $commands += ('scp -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes -i "{0}" "{1}" "{2}"' -f $identityResolved, $source, $destination)
}

$fence = [string]::new([char]96, 3)
$preLog = @(
    ""
    "## $date：同步服务器 Docker 配置快照（执行前登记）"
    ""
    "### 目的与范围"
    ""
    "只读从 $Server 获取五份 Compose 及其挂载配置，覆盖 resources/docker/server/ 中的同名配置文件。排除 .env、JAR 和 frontend-dist，不执行服务器端 Docker 命令。"
    ""
    "### 本地 SCP 命令（执行前登记）"
    ""
    ($fence + "powershell")
) + $commands + @(
    ($fence)
    ""
    "### 时间、结果与异常"
    ""
    "待执行后补记。"
)
Add-Utf8Text -Path $opsLog -Text ($preLog -join [Environment]::NewLine)

$failure = $null
try {
    foreach ($file in $files) {
        $source = $Server + ":" + $file.Remote
        $destination = Join-Path $stageDir $file.Name
        $scpArgs = @(
            "-o", "BatchMode=yes",
            "-o", "IdentitiesOnly=yes",
            "-o", "ConnectTimeout=10",
            "-o", "StrictHostKeyChecking=yes",
            "-i", $identityResolved,
            $source,
            $destination
        )
        & scp @scpArgs
        if ($LASTEXITCODE -ne 0) {
            throw "SCP failed for $($file.Remote) with exit code $LASTEXITCODE"
        }
        if (-not (Test-Path -LiteralPath $destination) -or (Get-Item -LiteralPath $destination).Length -eq 0) {
            throw "Downloaded file is missing or empty: $($file.Name)"
        }
    }

    foreach ($file in $files) {
        Move-Item -LiteralPath (Join-Path $stageDir $file.Name) -Destination (Join-Path $targetDir $file.Name) -Force
    }
}
catch {
    $failure = $_
}
finally {
    if (Test-Path -LiteralPath $stageDir) {
        Get-ChildItem -LiteralPath $stageDir -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
        Remove-Item -LiteralPath $stageDir -Force
    }
}

$finished = Get-Date
if ($null -eq $failure) {
    $sizes = foreach ($file in $files) {
        $item = Get-Item -LiteralPath (Join-Path $targetDir $file.Name)
        "$($file.Name) ($($item.Length) 字节)"
    }
    $resultText = @(
        ""
        "### 执行结果"
        ""
        "执行时间：$($finished.ToString('yyyy-MM-dd HH:mm:ss'))（本地时区）。"
        ""
        "- SCP 成功同步五份配置到 resources/docker/server/：$($sizes -join '、')。"
        "- 未读取或复制 .env、JAR 或 frontend-dist；未执行服务器端 Docker 命令；服务器状态未改变。"
        "- 异常：无。"
    ) -join [Environment]::NewLine
    Add-Utf8Text -Path $opsLog -Text $resultText

    $dailyLog = Join-Path $repoRoot ("docs/change-log/" + $date + ".md")
    if (-not (Test-Path -LiteralPath $dailyLog)) {
        [System.IO.File]::WriteAllText($dailyLog, "# 项目变更日志" + [Environment]::NewLine, $utf8NoBom)
    }
    $changeText = @(
        ""
        "## $($finished.ToString('yyyy-MM-dd HH:mm:ss'))：同步服务器 Docker 配置快照"
        ""
        "- 从服务器同步五份 Compose 及挂载配置到 resources/docker/server/；同步来源与排除项详见 docs/server-operations.md。"
        "- 未复制环境变量文件、应用 JAR 或前端构建产物。"
    ) -join [Environment]::NewLine
    Add-Utf8Text -Path $dailyLog -Text $changeText

    $indexText = [System.IO.File]::ReadAllText($changeIndex)
    $relativeEntry = "change-log/" + $date + ".md"
    if (-not $indexText.Contains($relativeEntry)) {
        Add-Utf8Text -Path $changeIndex -Text "| $date | 同步服务器 Docker 配置快照 | [查看当日记录]($relativeEntry) |"
    }
    Write-Output "配置同步完成：$($files.Count) 个文件；操作和变更已登记。"
}
else {
    $errorText = @(
        ""
        "### 执行结果"
        ""
        "执行时间：$($finished.ToString('yyyy-MM-dd HH:mm:ss'))（本地时区）。"
        ""
        "- SCP 或本地文件更新失败：$($failure.Exception.Message)"
        "- 服务器未执行 Docker 命令；请检查后重试。"
    ) -join [Environment]::NewLine
    Add-Utf8Text -Path $opsLog -Text $errorText
    throw $failure
}
