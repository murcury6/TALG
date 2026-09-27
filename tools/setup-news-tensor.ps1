$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$basePython = Join-Path $project '.pixi/envs/default/python.exe'
$environment = Join-Path $project 'work/news-tensor/venv'
if (!(Test-Path -LiteralPath (Join-Path $environment 'Scripts/python.exe'))) {
    & $basePython -m venv --system-site-packages $environment
    if ($LASTEXITCODE -ne 0) { throw 'Could not create news tensor environment' }
}
$python = Join-Path $environment 'Scripts/python.exe'
& $python -m pip install -r (Join-Path $PSScriptRoot 'news-tensor-requirements.txt') --disable-pip-version-check
if ($LASTEXITCODE -ne 0) { throw 'Could not install tensor runtime dependencies' }
$folder = Join-Path $project 'work/news-tensor/model'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$revision = '2c4055b12046f11709e9df2c122e59ffbdc2f900'
foreach ($name in @('tokenizer.json','config.json','tokenizer_config.json','onnx/model_quantized.onnx')) {
    $destination = Join-Path $folder ([IO.Path]::GetFileName($name))
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -Uri ('https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/'+$revision+'/'+$name) -OutFile ($destination+'.part') -TimeoutSec 180
        Move-Item -LiteralPath ($destination+'.part') -Destination $destination
    }
}
if ((Get-FileHash -LiteralPath (Join-Path $folder 'model_quantized.onnx') -Algorithm SHA256).Hash -ne '66FC00F5F29AFCAFF34092E1BDD20008CA3918265A82FB9695A551E510CC4EBC') {
    throw 'Downloaded model checksum differs from the tested model'
}
Write-Output 'Tensor runtime ready. Start with tools/start-news-tensor.ps1.'
