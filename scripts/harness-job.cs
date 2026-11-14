// 해당 실행의 process tree만 관리하기 위한 Windows Job Object 경계
using System;
using System.Text;
using System.Diagnostics;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace PixelHarness {
    public sealed class JobRun : IDisposable {
        [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
            public long ProcessTime, JobTime; public uint Flags;
            public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveLimit;
            public UIntPtr Affinity; public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong A,B,C,D,E,F; }
        [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
            public BasicLimits Basic; public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [StructLayout(LayoutKind.Sequential)] struct Accounting {
            public long A,B,C,D; public uint PageFaults, TotalProcesses, ActiveProcesses, TerminatedProcesses;
        }
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct StartupInfo {
            public uint Size; public string Reserved, Desktop, Title;
            public uint X,Y,XSize,YSize,XChars,YChars,Fill,Flags;
            public ushort Show, ReservedSize; public IntPtr ReservedBytes, Input, Output, Error;
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInfo { public IntPtr Process, Thread; public uint Id, ThreadId; }
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,ref ExtendedLimits limits,uint size);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,out Accounting info,uint size,IntPtr length);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr job,uint code);
        [DllImport("kernel32.dll", EntryPoint="CreateProcessW", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcess(string application,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string directory,ref StartupInfo startup,out ProcessInfo process);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle,uint milliseconds);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process,uint code);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        IntPtr job, process;
        public uint ProcessId { get; private set; }
        public bool Wait(int milliseconds) {
            uint result = WaitForSingleObject(process, (uint)milliseconds);
            if (result == 0) return true;
            if (result == 258) return false;
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public int ExitCode {
            get { uint code; if (!GetExitCodeProcess(process,out code)) throw new Win32Exception(Marshal.GetLastWin32Error()); return unchecked((int)code); }
        }
        // 자식이 실행되기 전에 job에 배속하여 등록 전 손자 process 생성 방지
        public JobRun(string executable,string encodedCommand,string directory) {
            job = CreateJobObject(IntPtr.Zero,null);
            if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits = new ExtendedLimits(); limits.Basic.Flags = 0x2000;
            ProcessInfo pi = new ProcessInfo(); bool created = false;
            try {
                if (!SetInformationJobObject(job,9,ref limits,(uint)Marshal.SizeOf(typeof(ExtendedLimits)))) throw new Win32Exception(Marshal.GetLastWin32Error());
                var si = new StartupInfo(); si.Size=(uint)Marshal.SizeOf(typeof(StartupInfo));
                var cmd = new StringBuilder("\""+executable+"\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "+encodedCommand);
                if (!CreateProcess(executable,cmd,IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,directory,ref si,out pi)) throw new Win32Exception(Marshal.GetLastWin32Error());
                created=true; process=pi.Process; ProcessId=pi.Id;
                if (!AssignProcessToJobObject(job,process)) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (ResumeThread(pi.Thread)==UInt32.MaxValue) throw new Win32Exception(Marshal.GetLastWin32Error());
            } catch {
                if (created) TerminateProcess(pi.Process,30);
                Dispose(); throw;
            } finally { if (pi.Thread!=IntPtr.Zero) CloseHandle(pi.Thread); }
        }
        public bool StopAndConfirm(int cleanupMilliseconds) {
            if (!TerminateJobObject(job,20)) throw new Win32Exception(Marshal.GetLastWin32Error());
            var watch=Stopwatch.StartNew();
            do {
                Accounting info;
                if (!QueryInformationJobObject(job,1,out info,(uint)Marshal.SizeOf(typeof(Accounting)),IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (info.ActiveProcesses==0) return true;
                System.Threading.Thread.Sleep(25);
            } while (watch.ElapsedMilliseconds<cleanupMilliseconds);
            return false;
        }
        public void Dispose() {
            // timeout·예외·정상 종료 모두 잔여 자손이 실행을 이어가지 못하도록 정리
            if (job!=IntPtr.Zero) { CloseHandle(job); job=IntPtr.Zero; }
            if (process!=IntPtr.Zero) { CloseHandle(process); process=IntPtr.Zero; }
        }
    }
}
