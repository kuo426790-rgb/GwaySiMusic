# MeloTune APK ကို Android ဖုန်းတစ်လုံးတည်းနဲ့ Build လုပ်နည်း

ဒီ ZIP ထဲမှာ GitHub Actions workflow ပါပြီးသားဖြစ်သည်။ GitHub က Cloud မှာ APK build ပေးမည်။

1. ZIP ကို extract လုပ်ပါ။
2. https://github.com ကိုဖွင့်ပြီး sign in လုပ်ပါ။
3. New repository တစ်ခုဖန်တီးပါ။ ဥပမာ `MeloTune`။
4. Extract လုပ်ထားသော `MeloTune` folder အတွင်းရှိ ဖိုင်များနှင့် `.github` folder ကို repository root သို့ upload လုပ်ပါ။ Folder structure ကို မပျက်စေရန် သတိပြုပါ။ Root မှာ `settings.gradle.kts`, `build.gradle.kts`, `app/`, `.github/` တို့ရှိရမည်။
5. GitHub repository မှ Actions tab ကိုဖွင့်ပါ။ Workflow မပေါ်လျှင် `.github/workflows/android-apk.yml` သည် `main` branch ပေါ်ရှိမရှိစစ်ပါ။
6. `Build MeloTune APK` ကိုရွေး၊ `Run workflow` ကိုနှိပ်ပါ။
7. Build ပြီးအောင်စောင့်ပါ။ အစိမ်းရောင် check ဖြစ်လာလျှင် run ကိုဖွင့်ပြီး Artifacts အောက်က `MeloTune-debug-apk` ကို download လုပ်ပါ။
8. Artifact ZIP ကိုဖုန်းမှာ extract လုပ်ပြီး `app-debug.apk` ကိုနှိပ်၍ install လုပ်ပါ။ Android က prompt ပြရင် ယုံကြည်ရသော ကိုယ်ပိုင် build ဖြစ်ကြောင်း စစ်ပြီး browser/file manager အတွက် install permission ကိုသာခွင့်ပြုပါ။

မှတ်ချက်များ:
- GitHub account နဲ့ internet လိုအပ်သည်။ Public repository သုံးနိုင်သော်လည်း source code ကို အများမြင်နိုင်မည်။ Private repository လည်း သုံးနိုင်သည်၊ သို့သော် Actions quota/limits သက်ဆိုင်နိုင်သည်။
- ဒါက debug APK ဖြစ်ပြီး Play Store release အတွက် signed release build မဟုတ်ပါ။
- YouTube search က ရလဒ်များကိုဖွင့်ပေးသည်၊ YouTube မှ MP3 ကို တိုက်ရိုက်ဆွဲယူမည်မဟုတ်ပါ။
