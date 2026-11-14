// 시작 순간부터 후손을 소유하는 Windows Job. 강제 종료·기존 프로세스 채택 없음
namespace Phase18Cap {
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;
 public sealed class JobCapture {
  [StructLayout(LayoutKind.Sequential)] struct SA { public int Length; public IntPtr Descriptor; public int Inherit; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct SI { public int Size; public string Reserved,Desktop,Title; public int X,Y,XSize,YSize,XCount,YCount,Fill,Flags; public short Show,Reserved2; public IntPtr ReservedPtr,Input,Output,Error; }
  [StructLayout(LayoutKind.Sequential)] struct SIX { public SI Info; public IntPtr Attributes; }
  [StructLayout(LayoutKind.Sequential)] struct PI { public IntPtr Process,Thread; public int Pid,Tid; }
  [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool CreatePipe(out IntPtr read,out IntPtr write,ref SA attributes,int size);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetHandleInformation(IntPtr handle,int mask,int flags);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool InitializeProcThreadAttributeList(IntPtr list,int count,int flags,ref IntPtr size);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool UpdateProcThreadAttribute(IntPtr list,uint flags,IntPtr attribute,IntPtr value,IntPtr size,IntPtr previous,IntPtr returned);
  [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string app,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr environment,string cwd,ref SIX startup,out PI process);
  [DllImport("kernel32.dll",SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,IntPtr result,int bytes,out int returned);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int kind);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateFile(string path,uint access,uint share,ref SA security,uint disposition,uint flags,IntPtr template);
  public Process Process; public Phase17C.StreamCapture Out=new Phase17C.StreamCapture(),Err=new Phase17C.StreamCapture(); public Task OutTask,ErrTask;
  IntPtr job;
  static void Check(bool ok) { if(!ok) throw new Win32Exception(Marshal.GetLastWin32Error()); }
  public static void ProtectStandardHandles() {
   foreach(int kind in new[]{-10,-11,-12}) { IntPtr h=GetStdHandle(kind);if(h!=IntPtr.Zero && h!=(IntPtr)(-1))Check(SetHandleInformation(h,1,0)); }
  }
  public static Process StartService(string executable,string arguments,string cwd,string stdout,string stderr) {
   SA sa=new SA {Length=Marshal.SizeOf(typeof(SA)),Inherit=1};
   IntPtr output=IntPtr.Zero,error=IntPtr.Zero,input=IntPtr.Zero,attrs=IntPtr.Zero,handles=IntPtr.Zero,size=IntPtr.Zero;PI pi=new PI();bool initialized=false;
   try {
    output=CreateFile(stdout,0x40000000,1,ref sa,1,0,IntPtr.Zero);Check(output!=(IntPtr)(-1));
    error=CreateFile(stderr,0x40000000,1,ref sa,1,0,IntPtr.Zero);Check(error!=(IntPtr)(-1));
    input=CreateFile("NUL",0x80000000,3,ref sa,3,0,IntPtr.Zero);Check(input!=(IntPtr)(-1));
    InitializeProcThreadAttributeList(IntPtr.Zero,1,0,ref size);attrs=Marshal.AllocHGlobal(size);
    Check(InitializeProcThreadAttributeList(attrs,1,0,ref size));initialized=true;
    handles=Marshal.AllocHGlobal(IntPtr.Size*3);Marshal.WriteIntPtr(handles,0,input);Marshal.WriteIntPtr(handles,IntPtr.Size,output);Marshal.WriteIntPtr(handles,IntPtr.Size*2,error);
    Check(UpdateProcThreadAttribute(attrs,0,(IntPtr)0x20002,handles,(IntPtr)(IntPtr.Size*3),IntPtr.Zero,IntPtr.Zero));
    SIX si=new SIX {Info=new SI {Size=Marshal.SizeOf(typeof(SIX)),Flags=0x100,Input=input,Output=output,Error=error},Attributes=attrs};
    Check(CreateProcess(executable,new StringBuilder(Quote(executable)+" "+arguments),IntPtr.Zero,IntPtr.Zero,true,0x00080008,IntPtr.Zero,cwd,ref si,out pi));
    Process p=Process.GetProcessById(pi.Pid);IntPtr retained=p.Handle;return p;
   } finally {foreach(IntPtr h in new[]{output,error,input,pi.Process,pi.Thread})if(h!=IntPtr.Zero && h!=(IntPtr)(-1))CloseHandle(h);if(initialized)DeleteProcThreadAttributeList(attrs);if(attrs!=IntPtr.Zero)Marshal.FreeHGlobal(attrs);if(handles!=IntPtr.Zero)Marshal.FreeHGlobal(handles);}
  }
  public static string Quote(string value) {
   if(value==null || value.IndexOf('\0')>=0 || value.IndexOf('\r')>=0 || value.IndexOf('\n')>=0) throw new ArgumentException("Invalid native argument");
   var b=new StringBuilder("\""); int slashes=0;
   foreach(char c in value) { if(c=='\\') { slashes++; continue; } if(c=='\"') b.Append('\\',slashes*2+1).Append(c); else b.Append('\\',slashes).Append(c); slashes=0; }
   return b.Append('\\',slashes*2).Append('"').ToString();
  }
  public int[] Members() {
   if(job==IntPtr.Zero) throw new InvalidOperationException("Job ownership unavailable");
   int bytes=8+4096*IntPtr.Size,returned; IntPtr data=Marshal.AllocHGlobal(bytes);
   try { Check(QueryInformationJobObject(job,3,data,bytes,out returned)); int assigned=Marshal.ReadInt32(data),count=Marshal.ReadInt32(data,4);
    if(count>4096 || assigned!=count) throw new InvalidOperationException("Incomplete job membership");
    int[] ids=new int[count];for(int i=0;i<count;i++)ids[i]=(int)Marshal.ReadIntPtr(data,8+i*IntPtr.Size);return ids;
   } finally { Marshal.FreeHGlobal(data); }
  }
  public void Start(string executable,string arguments,string cwd,string stdout,string stderr,long limit) {
   if(job!=IntPtr.Zero)throw new InvalidOperationException("Single use capture");
   job=CreateJobObject(IntPtr.Zero,null);Check(job!=IntPtr.Zero);
   IntPtr readOut=IntPtr.Zero,writeOut=IntPtr.Zero,readErr=IntPtr.Zero,writeErr=IntPtr.Zero,readIn=IntPtr.Zero,writeIn=IntPtr.Zero;
   IntPtr attrs=IntPtr.Zero,jobValue=IntPtr.Zero,size=IntPtr.Zero; PI pi=new PI(); bool initialized=false;
   try {
    SA sa=new SA {Length=Marshal.SizeOf(typeof(SA)),Inherit=1};
    Check(CreatePipe(out readOut,out writeOut,ref sa,0));Check(SetHandleInformation(readOut,1,0));
    Check(CreatePipe(out readErr,out writeErr,ref sa,0));Check(SetHandleInformation(readErr,1,0));
    Check(CreatePipe(out readIn,out writeIn,ref sa,0));Check(SetHandleInformation(writeIn,1,0));
    InitializeProcThreadAttributeList(IntPtr.Zero,1,0,ref size);attrs=Marshal.AllocHGlobal(size);
    Check(InitializeProcThreadAttributeList(attrs,1,0,ref size));initialized=true;
    jobValue=Marshal.AllocHGlobal(IntPtr.Size);Marshal.WriteIntPtr(jobValue,job);
    Check(UpdateProcThreadAttribute(attrs,0,(IntPtr)0x2000d,jobValue,(IntPtr)IntPtr.Size,IntPtr.Zero,IntPtr.Zero));
    SIX si=new SIX {Info=new SI {Size=Marshal.SizeOf(typeof(SIX)),Flags=0x100,Input=readIn,Output=writeOut,Error=writeErr},Attributes=attrs};
    // Job 결합 실패 시 프로세스 생성 자체 실패. 할당 전 실행 race 배제
    Check(CreateProcess(executable,new StringBuilder(Quote(executable)+" "+arguments),IntPtr.Zero,IntPtr.Zero,true,0x08080004,IntPtr.Zero,cwd,ref si,out pi));
    Process=Process.GetProcessById(pi.Pid);
    // 종료 뒤에도 실제 exit code 관측에 필요한 managed handle 유지
    IntPtr retained=Process.Handle;
    var output=new StreamReader(new FileStream(new SafeFileHandle(readOut,true),FileAccess.Read),Encoding.UTF8);readOut=IntPtr.Zero;
    var error=new StreamReader(new FileStream(new SafeFileHandle(readErr,true),FileAccess.Read),Encoding.UTF8);readErr=IntPtr.Zero;
    OutTask=Task.Run(()=>{using(output)Out.Pump(output,stdout,limit);});
    ErrTask=Task.Run(()=>{using(error)Err.Pump(error,stderr,limit);});
   } finally {
    foreach(IntPtr h in new[]{writeOut,writeErr,readIn,writeIn,readOut,readErr})if(h!=IntPtr.Zero)CloseHandle(h);
    if(pi.Thread!=IntPtr.Zero) { uint resumed=ResumeThread(pi.Thread); CloseHandle(pi.Thread);if(resumed==0xffffffff)throw new Win32Exception(Marshal.GetLastWin32Error()); }
    if(pi.Process!=IntPtr.Zero)CloseHandle(pi.Process);
    if(initialized)DeleteProcThreadAttributeList(attrs);if(attrs!=IntPtr.Zero)Marshal.FreeHGlobal(attrs);if(jobValue!=IntPtr.Zero)Marshal.FreeHGlobal(jobValue);
   }
  }
  public void Release() { if(Members().Length!=0)throw new InvalidOperationException("Owned processes remain");if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;} }
 }
}
