Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
cmd = Chr(34) & base & "\node.exe" & Chr(34) & " " & Chr(34) & base & "\agent.js" & Chr(34)
sh.Run cmd, 0, False
