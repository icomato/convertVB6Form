Imports System.Windows.Forms
Friend Module Program
    <System.STAThread()>
    Public Sub Main()
        Application.EnableVisualStyles()
        Application.SetCompatibleTextRenderingDefault(False)
        Application.Run(New ReportWindow())
    End Sub
End Module
