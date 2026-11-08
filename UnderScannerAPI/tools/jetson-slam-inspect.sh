#!/usr/bin/env bash
# READ-ONLY inspection of the Jetson's FAST-LIO setup (step 0 of the SLAM-profiles work).
# Changes nothing. Run from the PC:
#   ssh orin4slam@10.42.0.1 'bash -s' < UnderScannerAPI/tools/jetson-slam-inspect.sh
set +e
source /opt/ros/humble/setup.bash 2>/dev/null
source ~/ws_livox/install/setup.bash 2>/dev/null
source ~/ws_fastlio/install/setup.bash 2>/dev/null
WS=~/ws_fastlio
SRC=$WS/src/FAST_LIO_ROS2
SHARE=$(ros2 pkg prefix fast_lio 2>/dev/null)/share/fast_lio

h() { echo; echo "=================== $* ==================="; }

h "fast_lio package"
echo "prefix: $(ros2 pkg prefix fast_lio)"
git -C "$SRC" log -1 --format='commit %H %cd %s' 2>&1
git -C "$SRC" remote -v 2>&1 | head -2
git -C "$SRC" status --short 2>&1 | head -20

h "launch args (--show-args)"
timeout 30 ros2 launch fast_lio mapping.launch.py --show-args 2>&1

h "launch file"
cat "$SHARE/launch/mapping.launch.py" 2>&1

h "symlink-install? (config + launch in share/)"
ls -la "$SHARE/config/" "$SHARE/launch/" 2>&1
grep -o 'symlink[^"]*' $WS/build/fast_lio/colcon_command_prefix_build.sh 2>/dev/null | head -1
ls $WS/build/fast_lio/ 2>/dev/null | head -5

h "src vs installed mid360.yaml"
for f in "$SRC/config/mid360.yaml" "$SHARE/config/mid360.yaml"; do
  echo "$(readlink -f "$f")  sha256=$(sha256sum "$f" | cut -c1-16)"
done
diff "$SRC/config/mid360.yaml" "$SHARE/config/mid360.yaml" && echo "IDENTICAL"

h "installed mid360.yaml (what FAST-LIO actually reads today)"
cat -A "$SHARE/config/mid360.yaml" | sed 's/\$$//' | cat -n

h "map_file_path / map_save / pcd in source"
grep -n -E 'map_file_path|map_save|pcd_save|savePCDFile|declare_parameter.*filter_size|"common.lid_topic"|cloud_registered|"/Odometry"' \
  "$SRC"/src/*.cpp 2>/dev/null | head -60

h "backend python env"
cd ~/underscanner-api 2>/dev/null && ls -la
~/underscanner-api/venv/bin/python3 -c "import sys,yaml;print(sys.version.split()[0],'pyyaml',yaml.__version__, yaml.__file__)" 2>&1
~/underscanner-api/venv/bin/python3 -m pytest --version 2>&1 | head -1

h "service unit"
systemctl cat underscanner 2>&1 | head -40

h "data root"
ls -la /data/underscanner/ 2>&1
ls /data/underscanner/configs/ 2>&1 | tail -5
df -h /data 2>&1 | tail -1
