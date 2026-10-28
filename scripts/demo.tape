# SyncVault README demo — real CLI only (VHS).
# Run: bash scripts/record-demo.sh
#
# PS1 is "sv$ " (no trailing-space-only prompt): VHS Line scope strips
# trailing spaces, so Wait+Line /\$ $/ never matches a bare "$ ".
# After each foreground command: brief Sleep then Wait+Line /sv\$/.

Output docs/images/sync-demo.gif
Set Shell "bash"
Set FontSize 20
Set FontFamily "DejaVu Sans Mono"
Set Width 1280
Set Height 720
Set Theme "Catppuccin Mocha"
Set TypingSpeed 10ms
Set Framerate 12
Set PlaybackSpeed 1.3
Set Padding 10
Set Margin 0
Set CursorBlink false

Hide
Type `export PATH="/tmp/sv-bin:$PATH" PS1='sv$ ' PROMPT_COMMAND='' HISTCONTROL=ignorespace`
Enter
Type `cd /tmp/sv-demo-rec && rm -rf hub A B && mkdir hub A B && clear`
Enter
Sleep 200ms
Show

# --- Act 1 ---
Type `# 1) hub + two devices`
Enter
Sleep 500ms
Type `cd hub && syncvault init`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `syncvault serve 9418 >/tmp/sv-hub.log 2>&1 &`
Enter
Sleep 80ms
Wait+Line /sv\$/
Sleep 1s
Type `cd ../A && syncvault init && syncvault add-remote origin localhost:9418`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `cd ../B && syncvault init && syncvault add-remote origin localhost:9418`
Enter
Sleep 80ms
Wait+Line /sv\$/

Type `clear`
Enter
Sleep 80ms
Wait+Line /sv\$/

# --- Act 2 ---
Type `# 2) A commits and pushes first`
Enter
Type `cd ../A && printf 'sprite_v2\n' > asset.txt && syncvault add asset.txt && syncvault commit 'A: sprite_v2'`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `syncvault push origin master && echo "pushed OK"`
Enter
Sleep 80ms
Wait+Line /sv\$/
Sleep 250ms

Type `clear`
Enter
Sleep 80ms
Wait+Line /sv\$/

# --- Act 3 ---
Type `# 3) B diverges — push rejected`
Enter
Type `cd ../B && printf 'sprite_v3\n' > asset.txt && syncvault add asset.txt && syncvault commit 'B: sprite_v3'`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `syncvault push origin master`
Enter
Sleep 80ms
Wait+Screen /Please pull down remote changes before pushing/
Sleep 80ms
Wait+Line /sv\$/
Sleep 300ms

Type `clear`
Enter
Sleep 80ms
Wait+Line /sv\$/

# --- Act 4 ---
Type `# 4) B pulls, conflicts, resolves`
Enter
Type `syncvault pull origin master`
Enter
Sleep 80ms
Wait+Screen /Encountered a merge conflict/
Sleep 80ms
Wait+Line /sv\$/
Type `cat asset.txt`
Enter
Sleep 80ms
Wait+Line /sv\$/
Sleep 450ms
Type `syncvault resolve asset.txt --ours && syncvault commit 'resolve conflict'`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `syncvault push origin master && echo "pushed OK"`
Enter
Sleep 80ms
Wait+Line /sv\$/
Sleep 250ms

Type `clear`
Enter
Sleep 80ms
Wait+Line /sv\$/

# --- Act 5 ---
Type `# 5) verify merge + hub integrity`
Enter
Type `syncvault global-log | head -32`
Enter
Sleep 80ms
Wait+Line /sv\$/
Sleep 600ms
Type `clear`
Enter
Sleep 80ms
Wait+Line /sv\$/
Type `cd ../hub && syncvault fsck`
Enter
Sleep 80ms
Wait+Screen@30s /fsck: ok/
Sleep 80ms
Wait+Line /sv\$/
Sleep 1.5s

Hide
Type `pkill -f 'syncvault-0.1.0-SNAPSHOT.jar serve' 2>/dev/null || true`
Enter
Sleep 100ms
