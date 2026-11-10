$ErrorActionPreference='Stop'
$OS="E:\picture\deepseek-harness\default-workspace\asmr-mobile-os"
Set-Location $OS
git tag v1.0.0
git push origin v1.0.0
$n = "v1.0.0 首发"
gh release create v1.0.0 "NihonSubs-v1.0.0.apk" --repo T2887/NihonSubs --title "NihonSubs v1.0.0" --notes $n
Write-Host "done"