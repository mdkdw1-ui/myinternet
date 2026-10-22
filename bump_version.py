import re

with open("version.properties", "r") as f:
    content = f.read()

ver_name = float(re.search(r"VERSION_NAME=([\d.]+)", content).group(1))
ver_code = int(re.search(r"VERSION_CODE=(\d+)", content).group(1))

new_name = round(ver_name + 0.001, 3)
new_code = ver_code + 1

with open("version.properties", "w") as f:
    f.write(f"VERSION_NAME={new_name:.3f}\nVERSION_CODE={new_code}\n")

print(f"Bumped: {ver_name} -> {new_name:.3f} (code {ver_code} -> {new_code})")
