import pandas as pd
import matplotlib.pyplot as plt

# Load CSV
df = pd.read_csv("leap_log.csv")

# Convert time to seconds
df["time"] = df["time"] / 1000.0

# -------- Graph 1: CWND vs Time --------
plt.figure()
plt.plot(df["time"], df["cwnd"])
plt.xlabel("Time (seconds)")
plt.ylabel("Congestion Window (cwnd)")
plt.title("CWND vs Time")
plt.grid()
plt.savefig("cwnd_vs_time.png")

# -------- Graph 2: ssthresh vs Time --------
plt.figure()
plt.plot(df["time"], df["ssthresh"])
plt.xlabel("Time (seconds)")
plt.ylabel("ssthresh")
plt.title("ssthresh vs Time")
plt.grid()
plt.savefig("ssthresh_vs_time.png")

# -------- Graph 3: Events Timeline --------
# Filter important events
events = df[df["event"].isin(["TIMEOUT", "FAST_RETX"])]

plt.figure()
plt.scatter(events["time"], events["cwnd"], label="Events")
plt.xlabel("Time (seconds)")
plt.ylabel("cwnd")
plt.title("Loss Events (Timeout & Fast Retransmit)")
plt.legend()
plt.grid()
plt.savefig("events.png")

print("Graphs saved:")
print(" - cwnd_vs_time.png")
print(" - ssthresh_vs_time.png")
print(" - events.png")