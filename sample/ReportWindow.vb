' VB6から移植したイベント処理・ビジネスロジックは未移行です。
' 元.frmのコード末尾を確認して必要な処理を移植してください。
Partial Class ReportWindow
    Public Sub New()
        InitializeComponent()
    End Sub
    Public lNewReportFlag As Boolean
End Class
