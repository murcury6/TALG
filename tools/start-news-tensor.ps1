$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$python = Join-Path $project 'work/news-tensor/venv/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python)) { throw 'Create the news tensor environment first; see docs/NEWS_TENSOR.md.' }
Start-Process -FilePath $python -ArgumentList @('-X','faulthandler','-m','talg_py.news_tensor','--root',$project) -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $project 'work/news-tensor/worker-output.log') -RedirectStandardError (Join-Path $project 'work/news-tensor/worker-error.log')
