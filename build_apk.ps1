# ── 手工打包 APK（不依赖 Gradle）─────────────────────────────
# 项目结构遵循 AGP 标准布局：
#   app/src/main/{AndroidManifest.xml, java/, res/, assets/, jniLibs/}
# 用法：pwsh -File build_apk.ps1
$ErrorActionPreference = 'Stop'

$Sdk        = 'H:\software\ASMR翻译器\_androidsetup\sdk'
$Jdk        = 'H:\software\ASMR翻译器\_androidsetup\jdk'
$Bt         = Join-Path $Sdk 'build-tools'
$AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'

# d8.bat / apksigner.bat 靠 JAVA_HOME 找 java
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

$Proj  = $PSScriptRoot
$Main  = Join-Path $Proj 'app\src\main'
$Build = Join-Path $Proj 'build'
$Name  = 'asmr-mobile'

function KillDir($p) { if (Test-Path $p) { [System.IO.Directory]::Delete($p, $true) } }
KillDir $Build
New-Item -ItemType Directory -Force -Path `
    "$Build\res", "$Build\gen", "$Build\classes", "$Build\dex" | Out-Null

# ── 1) 编译资源 ──
Write-Host '[1/7] aapt2 compile（资源）'
& "$Bt\aapt2.exe" compile --dir (Join-Path $Main 'res') -o "$Build\res\"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败 ($LASTEXITCODE)" }
$flats = @(Get-ChildItem "$Build\res\*.flat" | ForEach-Object { $_.FullName })

# ── 2) 链接：生成 base.apk + R.java ──
Write-Host '[2/7] aapt2 link（生成 base.apk 与 R.java）'
& "$Bt\aapt2.exe" link `
    -o "$Build\base.apk" `
    -I $AndroidJar `
    --manifest (Join-Path $Main 'AndroidManifest.xml') `
    -A (Join-Path $Main 'assets') `
    --java "$Build\gen" `
    --debug-mode `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    @flats
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败 ($LASTEXITCODE)" }

# ── 3) javac ──
Write-Host '[3/7] javac（Java + R）'
$javaFiles = @(Get-ChildItem -Path (Join-Path $Main 'java') -Recurse -Filter *.java |
               ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem -Path "$Build\gen" -Recurse -Filter *.java |
                ForEach-Object { $_.FullName })
& "$Jdk\bin\javac.exe" -encoding UTF-8 -nowarn -classpath $AndroidJar -d "$Build\classes" @javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac 失败 ($LASTEXITCODE)" }

# ── 4) d8 ──
Write-Host '[4/7] d8（转 dex）'
$classFiles = Get-ChildItem -Path "$Build\classes" -Recurse -Filter *.class |
              ForEach-Object { $_.FullName }
& cmd /c "`"$Bt\d8.bat`" --lib `"$AndroidJar`" --min-api 29 --output `"$Build\dex`" $($classFiles -join ' ')"
if (-not (Test-Path "$Build\dex\classes.dex")) { throw 'd8 没有生成 classes.dex' }

# ── 5) 打包：classes.dex + jniLibs ──
Write-Host '[5/7] 写入 classes.dex 与 jniLibs'
Copy-Item "$Build\base.apk" "$Build\unsigned.apk" -Force
$jniDir = Join-Path $Main 'jniLibs\arm64-v8a'
$pyExe  = 'D:\ASMR翻译器\package\ASMR自动翻译工具\env\python.exe'
& $pyExe -c @"
import zipfile, os, sys
apk, dex, jni = sys.argv[1], sys.argv[2], sys.argv[3]
z = zipfile.ZipFile(apk, 'a', zipfile.ZIP_STORED)
z.write(dex, 'classes.dex')
n = 0
if os.path.isdir(jni):
    for f in sorted(os.listdir(jni)):
        p = os.path.join(jni, f)
        if os.path.isfile(p) and f.endswith('.so') and not f.endswith('.bak'):
            z.write(p, 'lib/arm64-v8a/' + f)
            n += 1
z.close()
print('[pack] classes.dex + %d 个 .so' % n)
"@ "$Build\unsigned.apk" "$Build\dex\classes.dex" $jniDir
if ($LASTEXITCODE -ne 0) { throw "写入 APK 失败 ($LASTEXITCODE)" }

# ── 6) zipalign ──
Write-Host '[6/7] zipalign'
& "$Bt\zipalign.exe" -f -p 4 "$Build\unsigned.apk" "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败 ($LASTEXITCODE)" }

# ── 7) 签名（release 自签名证书，首次自动生成）──
# 为什么不用 debug 证书：CN=Android Debug 是所有国产 ROM 安全检测和杀毒引擎的硬编码红线，
# 分发给别人装必然被拦（「未经安全检测」/「风险应用」）。release 证书能去掉这一条。
Write-Host '[7/7] 签名'
$ks = Join-Path $Proj 'asmr-release.keystore'
$ksPass = 'nihonsubs-2026'
$ksAlias = 'nihonsubs'
if (-not (Test-Path $ks)) {
    Write-Host '      首次运行：生成 release 证书（25 年有效期）'
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass $ksPass -keypass $ksPass `
        -alias $ksAlias -keyalg RSA -keysize 2048 -validity 9125 `
        -dname 'CN=NihonSubs, OU=Mobile, O=Japanese Subtitle Generator, L=Beijing, ST=Beijing, C=CN' `
        -ext "BasicConstraints=CA:FALSE" 2>&1 | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw "生成 release 证书失败 ($LASTEXITCODE)" }
}
$apk = Join-Path $Proj "$Name.apk"
& "$Bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --ks-key-alias $ksAlias `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $apk "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败 ($LASTEXITCODE)" }

Write-Host ''
Write-Host ("[OK] 打包完成: {0}  {1:N2} MB" -f $apk, ((Get-Item $apk).Length / 1MB))
