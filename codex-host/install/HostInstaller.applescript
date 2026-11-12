on run
	set bundlePath to POSIX path of (path to me)
	set nodePath to bundlePath & "Contents/Resources/runtime/node"
	set managerPath to bundlePath & "Contents/Resources/codex-host/install/manager.mjs"
	set installedPath to (POSIX path of (path to home folder)) & "Applications/Linear Lite 协作宿主.app/"
	set commandPrefix to (quoted form of nodePath) & " " & (quoted form of managerPath) & " "
	try
		if bundlePath is installedPath then
			set chosen to choose from list {"连接", "状态", "退出授权", "卸载"} with prompt "Linear Lite 协作宿主" default items {"状态"} OK button name "继续" cancel button name "取消"
			if chosen is false then return
			set action to item 1 of chosen
			if action is "连接" then
				set resultText to do shell script commandPrefix & "connect"
			else if action is "状态" then
				set resultText to do shell script commandPrefix & "status"
			else if action is "退出授权" then
				set resultText to do shell script commandPrefix & "disconnect"
			else
				display dialog "确认卸载协作宿主？设备标识、连接配置和授权保留。存在待恢复执行时，卸载会停止。" buttons {"取消", "卸载"} default button "取消" with icon caution
				set resultText to do shell script commandPrefix & "uninstall"
			end if
			display dialog resultText buttons {"好"} default button "好"
		else
			set siteOrigin to text returned of (display dialog "输入 Linear Lite 站点的 HTTPS 地址：" default answer "" buttons {"取消", "安装"} default button "安装")
			set resultText to do shell script commandPrefix & "install --source " & (quoted form of bundlePath) & " --origin " & (quoted form of siteOrigin)
			set choice to button returned of (display dialog resultText buttons {"稍后", "连接"} default button "连接")
			if choice is "连接" then
				set resultText to do shell script commandPrefix & "connect"
				display dialog resultText buttons {"好"} default button "好"
			end if
		end if
	on error errorText number errorNumber
		if errorNumber is not -128 then
			if errorText contains "本机端口被进程" then
				set choice to button returned of (display dialog errorText buttons {"打开活动监视器", "好"} default button "好" with icon caution)
				if choice is "打开活动监视器" then do shell script "/usr/bin/open -b com.apple.ActivityMonitor"
			else
				display dialog errorText buttons {"好"} default button "好" with icon caution
			end if
		end if
	end try
end run
