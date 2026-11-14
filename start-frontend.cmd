@echo off
echo Frontend: http://localhost:3000/
"C:\Users\cgt\AppData\Roaming\uv\tools\graphifyy\Scripts\python.exe" -u -B -m http.server 3000 --bind 127.0.0.1 --directory "%~dp0frontend"
pause