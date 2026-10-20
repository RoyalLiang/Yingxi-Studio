import SwiftUI
import UniformTypeIdentifiers

private let berry=Color(red:0.73,green:0.07,blue:0.28)
@main struct YingxiPlayerApp:App {
    @UIApplicationDelegateAdaptor(OrientationDelegate.self) var delegate
    @StateObject private var store=MediaStore()
    var body:some Scene {WindowGroup{RootView().environmentObject(store).tint(berry)}}
}
struct RootView:View {
    @EnvironmentObject private var store:MediaStore
    @State private var importing=false
    var body:some View {
        TabView {
            NavigationStack{home}.tabItem{Label("首页",systemImage:"house.fill")}
            NavigationStack{SourcesView()}.tabItem{Label("来源",systemImage:"externaldrive.connected.to.line.below")}
            NavigationStack{history}.tabItem{Label("播放记录",systemImage:"clock.arrow.circlepath")}
        }.fileImporter(isPresented:$importing,allowedContentTypes:[.movie],allowsMultipleSelection:false){result in
            switch result {case .success(let urls):if let url=urls.first{Task{await store.importFile(url)}};case .failure(let error):store.message=error.localizedDescription}
        }.fullScreenCover(item:$store.playing){target in PlaybackView(target:target,store:store)}
        .alert("提示",isPresented:Binding(get:{store.message != nil},set:{if !$0{store.message=nil}})){Button("知道了"){store.message=nil}}message:{Text(store.message ?? "")}
        .onOpenURL{url in if !store.cloudAuthorization.callback(url){store.message="授权会话不存在或已过期，请重新授权。"}}
        .onAppear{
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-ui-play-first"),let first=store.localFiles.first {DispatchQueue.main.asyncAfter(deadline:.now()+0.7){store.playLocal(first)}}
            #endif
        }
    }
    private var home:some View {
        List {
            if let recent=store.history.first {Section("继续观看"){Button{store.resume(recent)}label:{HStack{Image(systemName:"play.circle.fill").font(.largeTitle);VStack(alignment:.leading,spacing:5){Text(recent.title).font(.headline).lineLimit(1);Text(time(recent.position)+" / "+time(recent.duration)).font(.caption).foregroundStyle(.secondary)}}}}}
            Section{Button{importing=true}label:{Label(store.importing ? "正在导入…" : "导入本机视频",systemImage:"folder")}.disabled(store.importing)}footer:{Text("通过系统文件选择器导入；支持 iOS 原生可解码的视频。")}
            Section("本机片库") {
                if store.localFiles.isEmpty{ContentUnavailableView("你的片库，从这里开始",systemImage:"film.stack",description:Text("导入视频，或在来源页连接 HTTPS WebDAV。"))}
                ForEach(store.localFiles,id:\.lastPathComponent){url in Button{store.playLocal(url)}label:{Label(url.lastPathComponent,systemImage:"play.rectangle").lineLimit(2)}}
            }
        }.navigationTitle("映汐播放器").toolbar{ToolbarItem(placement:.topBarTrailing){NavigationLink{AboutView()}label:{Image(systemName:"gearshape")}}}
    }
    private var history:some View {
        List {
            if store.history.isEmpty{ContentUnavailableView("暂无播放记录",systemImage:"clock",description:Text("开始播放后，会在这里保留观看位置。"))}
            ForEach(store.history){row in Button{store.resume(row)}label:{VStack(alignment:.leading,spacing:5){Text(row.title).foregroundStyle(.primary).lineLimit(2);Text(time(row.position)+" / "+time(row.duration)).font(.caption).foregroundStyle(.secondary)}}}
        }.navigationTitle("播放记录").toolbar{Button("清除"){store.clearHistory()}.disabled(store.history.isEmpty)}
    }
}
struct SourcesView:View {
    @EnvironmentObject private var store:MediaStore
    @State private var adding=false
    @State private var deleting:DAVSource?
    var body:some View {
        List {
            Section("已保存来源") {
                Section("网盘"){
                ForEach(store.cloudSources){source in NavigationLink{CloudBrowser(source:source)}label:{Label(source.name,systemImage:"cloud.fill")}.swipeActions{Button("移除",role:.destructive){store.removeCloud(source)}}}
                CloudConnectButton()
            }
            if store.sources.isEmpty && store.cloudSources.isEmpty{ContentUnavailableView("连接你的片库",systemImage:"externaldrive",description:Text("支持 HTTPS WebDAV；SMB、SFTP 和网盘官方授权尚未接入 iOS 初版。"))}
                ForEach(store.sources){source in NavigationLink{DAVBrowser(source:source,url:source.baseURL)}label:{Label(source.name,systemImage:"externaldrive.connected.to.line.below")}.swipeActions{Button("删除",role:.destructive){deleting=source}}}
            }
        }.navigationTitle("来源").toolbar{Button{adding=true}label:{Image(systemName:"plus")}}
        .sheet(isPresented:$adding){AddDAVSource()}
        .confirmationDialog("删除此来源？",isPresented:Binding(get:{deleting != nil},set:{if !$0{deleting=nil}}),titleVisibility:.visible){Button("删除来源",role:.destructive){if let source=deleting{store.remove(source)};deleting=nil}}message:{Text("只移除播放器内的来源与关联播放记录，不删除服务器文件。")}
    }
}
struct AddDAVSource:View {
    @EnvironmentObject private var store:MediaStore
    @Environment(\.dismiss) private var dismiss
    @State private var name=""
    @State private var address="https://"
    @State private var username=""
    @State private var password=""
    @State private var error:String?
    @State private var busy=false
    @State private var checkTask:Task<Void,Never>?
    var body:some View {
        NavigationStack {
            Form {
                Section("HTTPS WebDAV") {TextField("名称（可选）",text:$name);TextField("服务器完整地址",text:$address).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()}
                Section("身份验证"){TextField("用户名（可选）",text:$username).textInputAutocapitalization(.never).autocorrectionDisabled();SecureField("密码",text:$password)}
                if let error{Text(error).foregroundStyle(.red)}
                Section{Button{connect()}label:{HStack{if busy{ProgressView()};Text(busy ? "验证来源…" : "验证并添加")}}.disabled(busy)}footer:{Text("密码保存在本机钥匙串；不会写入视频地址。只添加实际可读取的来源。")}
            }.navigationTitle("连接 WebDAV").navigationBarTitleDisplayMode(.inline).toolbar{ToolbarItem(placement:.cancellationAction){Button("取消"){dismiss()}}}
        }.onDisappear{checkTask?.cancel()}
    }
    private func connect(){
        busy=true;error=nil
        checkTask=Task {
            defer{busy=false}
            do {let source=try DAVSource(name:name,address:address,username:username);_ = try await DAVClient(source:source,password:password).list(source.baseURL);try Task.checkCancellation();try store.add(source,password:password);dismiss()}
            catch is CancellationError {}catch{if !Task.isCancelled{self.error=error.localizedDescription}}
        }
    }
}
struct DAVBrowser:View {
    @EnvironmentObject private var store:MediaStore
    let source:DAVSource
    let url:URL
    @State private var entries=[DAVEntry]()
    @State private var busy=true
    @State private var error:String?
    @State private var revision=0
    var body:some View {
        List {
            if busy{ProgressView("正在读取目录…")}
            if let error{Section{Text(error).foregroundStyle(.secondary);Button("重试"){revision+=1}}}
            ForEach(entries){entry in
                if entry.directory{NavigationLink{DAVBrowser(source:source,url:entry.url.hasDirectoryPath ? entry.url : entry.url.appendingPathComponent(""))}label:{Label(entry.name,systemImage:"folder")}}
                else if entry.isVideo{Button{store.playRemote(entry,source:source)}label:{Label(entry.name,systemImage:"play.rectangle").lineLimit(2)}}
            }
            if !busy && error==nil && entries.isEmpty{ContentUnavailableView("目录为空",systemImage:"folder")}
        }.navigationTitle(url.standardized.path==source.baseURL.standardized.path ? source.name : url.lastPathComponent).navigationBarTitleDisplayMode(.inline)
        .task(id:revision){busy=true;error=nil;do{let result=try await DAVClient(source:source,password:PasswordVault.read(source.id)).list(url);try Task.checkCancellation();entries=result}catch is CancellationError{}catch{if !Task.isCancelled{self.error=error.localizedDescription}};busy=false}
    }
}
struct AboutView:View {
    var body:some View {List{Section("映汐播放器"){Text("iOS 0.1.0-alpha.2");Text("面向 iOS 27 的首个原生验证版本").foregroundStyle(.secondary)};Section("当前能力"){Text("本地视频 · HTTPS WebDAV · 系统播放控件 · 播放记录");Text("SMB、SFTP、Android 自定义手势和解码能力尚未迁移。").foregroundStyle(.secondary)}}.navigationTitle("关于")}
}
private func time(_ value:Double)->String{let total=max(0,Int(value.isFinite ? value : 0));return total>=3600 ? String(format:"%d:%02d:%02d",total/3600,total/60%60,total%60) : String(format:"%02d:%02d",total/60,total%60)}
