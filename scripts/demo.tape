# SyncVault README demo — real CLI only (VHS).
# Run: bash scripts/record-demo.sh

Output docs/images/sync-demo.gif
Set Shell "bash"
Set FontSize 20
Set FontFamily "DejaVu Sans Mono"
Set Width 1280
Set Height 720
Set Theme "Catppuccin Mocha"
Set TypingSpeed 12ms
Set Framerate 12
Set PlaybackSpeed 1.05
Set Padding 10
Set Margin 0
Set CursorBlink false

Hide
Type `export PATH="/tmp/sv-bin:$PATH" PS1='$ ' PROMPT_COMMAND='' HISTCONTROL=ignorespace`
Enter
Type `cd /tmp/sv-demo-rec && rm -rf hub A B && mkdir hub A B`
Enter
Sleep 150ms
Show

Type `clear`
Enter
Sleep 150ms
Type `# 1) hub + two devices`
Enter
Type `cd hub && syncvault init`
Enter
Sleep 200ms
Type `syncvault serve 9418 >/tmp/sv-hub.log 2>&1 &`
Enter
Sleep 550ms
Type `cd ../A && syncvault init && syncvault add-remote origin localhost:9418`
Enter
Sleep 250ms
Type `cd ../B && syncvault init && syncvault add-remote origin localhost:9418`
Enter
Sleep 300ms

Type `clear`
Enter
Sleep 120ms
Type `# 2) A commits and pushes first`
Enter
Type `cd ../A && printf 'sprite_v2\n' > asset.txt && syncvault add asset.txt && syncvault commit 'A: sprite_v2'`
Enter
Sleep 280ms
Type `syncvault push origin master`
Enter
Sleep 600ms

Type `clear`
Enter
Sleep 120ms
Type `# 3) B diverges — push rejected`
Enter
Type `cd ../B && printf 'sprite_v3\n' > asset.txt && syncvault add asset.txt && syncvault commit 'B: sprite_v3'`
Enter
Sleep 280ms
Type `syncvault push origin master`
Enter
Sleep 1000ms

Type `clear`
Enter
Sleep 120ms
Type `# 4) B pulls, conflicts, resolves`
Enter
Type `syncvault pull origin master`
Enter
Sleep 550ms
Type `cat asset.txt`
Enter
Sleep 900ms
Type `syncvault resolve asset.txt --ours && syncvault commit 'resolve conflict'`
Enter
Sleep 350ms
Type `syncvault push origin master`
Enter
Sleep 600ms

Type `clear`
Enter
Sleep 120ms
Type `# 5) verify merge + hub integrity`
Enter
Type `syncvault log | head -14`
Enter
Sleep 700ms
Type `cd ../hub && syncvault fsck`
Enter
Sleep 1600ms

Hide
Type `pkill -f 'syncvault-0.1.0-SNAPSHOT.jar serve' 2>/dev/null || true`
Enter
Sleep 100ms
