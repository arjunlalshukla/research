for f in $(awk '{print $2}' collector_onyx.txt | uniq)
do
  echo "Killing procs on $f"
  ssh $f "ps -augx | grep ./target/release/device_main ; pkill -f ./target/release/device_main"
done