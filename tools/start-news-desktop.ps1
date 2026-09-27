$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar = Join-Path $project 'target/talg-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build TALG first: .tools/pixi/pixi.exe run mvn package' }
Start-Process -FilePath (Join-Path $project '.pixi/envs/default/Library/lib/jvm/bin/javaw.exe') -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory $project -WindowStyle Hidden
