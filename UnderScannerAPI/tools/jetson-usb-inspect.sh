#!/usr/bin/env bash
# READ-ONLY inspection for the "copy a scan to a USB stick" feature. Mounts nothing,
# changes nothing. Plug the USB stick in first, then from the PC (Git Bash):
#   ssh orin4slam@10.42.0.1 'bash -s' < UnderScannerAPI/tools/jetson-usb-inspect.sh > usb0.txt 2>&1
set +e
h() { echo; echo "=================== $* ==================="; }

h "user / groups"
id

h "udisks2"
command -v udisksctl || echo "udisksctl: ABSENT"
systemctl is-active udisks2 2>&1
udisksctl status 2>&1 | head -20

h "block devices (lsblk, bytes)"
lsblk -b -o NAME,PATH,TYPE,SIZE,FSTYPE,FSVER,LABEL,MODEL,VENDOR,TRAN,RM,HOTPLUG,MOUNTPOINT,FSAVAIL 2>&1
h "block devices (lsblk -J, what the backend would parse)"
lsblk -J -b -o NAME,PATH,TYPE,SIZE,FSTYPE,LABEL,MODEL,VENDOR,TRAN,RM,HOTPLUG,MOUNTPOINT,FSAVAIL 2>&1

h "current mounts of USB-ish devices"
findmnt -rn -o SOURCE,TARGET,FSTYPE,OPTIONS 2>/dev/null | grep -E '^/dev/sd|/media/|/mnt/' || echo "(none)"
ls -la /media /media/$USER /mnt 2>&1

h "polkit (0.105 = .pkla files; >= 0.106 = JS rules)"
pkaction --version 2>&1
dpkg -l | grep -E 'polkit|policykit' 2>&1 | awk '{print $2, $3}'
ls -la /etc/polkit-1/rules.d /etc/polkit-1/localauthority/50-local.d /var/lib/polkit-1/localauthority/50-local.d 2>&1

h "would udisks let THIS (ssh, no active seat) user mount? (pkcheck only asks, mounts nothing)"
for a in org.freedesktop.udisks2.filesystem-mount org.freedesktop.udisks2.filesystem-mount-other-seat \
         org.freedesktop.udisks2.filesystem-unmount-others org.freedesktop.udisks2.power-off-drive \
         org.freedesktop.udisks2.power-off-drive-other-seat; do
  pkcheck --action-id "$a" --process $$ >/dev/null 2>&1
  echo "$a -> exit $? (0 = allowed, 1/2 = not authorized / needs auth)"
done
loginctl list-sessions 2>&1 | head -10

h "filesystems the kernel can mount"
grep -E 'vfat|exfat|ntfs|ext4|fuseblk' /proc/filesystems
command -v mount.exfat mount.exfat-fuse mount.ntfs-3g ntfs-3g 2>&1

h "rsync"
rsync --version 2>&1 | head -1

h "sudo rules for this user (non-interactive; fails harmlessly if a password is needed)"
sudo -n -l 2>&1 | tail -15

h "biggest single files in recent bags (FAT32 = 4 GiB max per file)"
find /data/underscanner/bags -type f -printf '%s\t%p\n' 2>/dev/null | sort -n | tail -5 | \
  awk -F'\t' '{printf "%.2f GiB\t%s\n", $1/2^30, $2}'
du -sh /data/underscanner/bags 2>/dev/null
