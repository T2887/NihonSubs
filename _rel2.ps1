$OS="E:\picture\deepseek-harness\default-workspace\asmr-mobile-os"
Set-Location $OS
git add -A
git commit -q -m "版本自检：Gitee 兜底地址改为 aaa15/NihonSubs；README 加 Gitee 说明"
git push origin main
Write-Host "code pushed"
gh release upload v1.0.0 "NihonSubs-v1.0.0.apk" --repo T2887/NihonSubs --clobber
Write-Host "asset replaced"