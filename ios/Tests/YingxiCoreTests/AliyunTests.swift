import XCTest
@testable import YingxiCore

private final class CloudStub:URLProtocol,@unchecked Sendable {
    static var handler:((URLRequest)throws->(Int,Data))?
    override class func canInit(with request:URLRequest)->Bool{true}
    override class func canonicalRequest(for request:URLRequest)->URLRequest{request}
    override func startLoading(){
        do{guard let handler=Self.handler else{throw CloudError.invalidResponse};let (status,data)=try handler(request)
            let response=HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:"HTTP/1.1",headerFields:["Content-Type":"application/json","Content-Length":String(data.count)])!
            client?.urlProtocol(self,didReceive:response,cacheStoragePolicy:.notAllowed);client?.urlProtocol(self,didLoad:data);client?.urlProtocolDidFinishLoading(self)
        }catch{client?.urlProtocol(self,didFailWithError:error)}
    }
    override func stopLoading(){}
}
final class AliyunTests:XCTestCase {
    override func tearDown(){CloudStub.handler=nil;super.tearDown()}
    private func api()->AliyunAPI{let config=URLSessionConfiguration.ephemeral;config.protocolClasses=[CloudStub.self];return AliyunAPI(base:URL(string:"https://api.example.invalid/")!,configuration:config)}
    func testPKCEHasSecureLengthAndIndependentState()throws{
        var seen=Set<String>()
        for _ in 0..<500{let grant=try AliyunPKCE();XCTAssertEqual(grant.verifier.count,43);XCTAssertEqual(grant.challenge.count,43);XCTAssertNotEqual(grant.verifier,grant.state);XCTAssertTrue(seen.insert(grant.verifier).inserted);XCTAssertFalse(grant.description.contains(grant.verifier))}
    }
    func testExchangeSupportsMissingRefreshWithoutClientSecret()async throws{
        CloudStub.handler={request in
            var data=request.httpBody ?? Data()
            if data.isEmpty,let stream=request.httpBodyStream{stream.open();defer{stream.close()};var bytes=[UInt8](repeating:0,count:1024);while stream.hasBytesAvailable{let count=stream.read(&bytes,maxLength:bytes.count);if count<=0{break};data.append(contentsOf:bytes.prefix(count))}}
            let body=try JSONSerialization.jsonObject(with:data) as! [String:Any]
            XCTAssertEqual(body["grant_type"] as? String,"authorization_code");XCTAssertNotNil(body["code_verifier"]);XCTAssertNil(body["client_secret"]);XCTAssertNil(request.value(forHTTPHeaderField:"Authorization"))
            return (200,Data("{\"access_token\":\"fixture-token\",\"expires_in\":3600}".utf8))
        }
        let token=try await api().exchange(code:"fixture-code",verifier:"fixture-verifier")
        XCTAssertNil(token.refresh);XCTAssertGreaterThan(token.expires.timeIntervalSinceNow,3500);XCTAssertFalse(token.description.contains(token.access))
    }
    func testPaginationSortingAndStableIdentifiers()async throws{
        var calls=0
        CloudStub.handler={request in
            calls+=1;XCTAssertEqual(request.value(forHTTPHeaderField:"Authorization"),"Bearer fixture-token")
            if calls==1{return(200,Data("{\"items\":[{\"file_id\":\"id-10\",\"name\":\"10.mp4\",\"type\":\"file\"}],\"next_marker\":\"next\"}".utf8))}
            return(200,Data("{\"items\":[{\"file_id\":\"id-2\",\"name\":\"2.mp4\",\"type\":\"file\"},{\"file_id\":\"folder\",\"name\":\"剧集\",\"type\":\"folder\"}]}".utf8))
        }
        let rows=try await api().list("fixture-token",drive:"drive",parent:"root")
        XCTAssertEqual(rows.map(\.id),["folder","id-2","id-10"]);XCTAssertEqual(calls,2)
    }
    func testRepeatedMarkerIsRejected()async throws{
        CloudStub.handler={_ in(200,Data("{\"items\":[],\"next_marker\":\"same\"}".utf8))}
        do{_ = try await api().list("fixture-token",drive:"drive",parent:"root");XCTFail("repeated pagination accepted")}catch CloudError.invalidResponse{}catch{XCTFail("unexpected error")}
    }
    func testDownloadRejectsCredentialsAndCleartext()async throws{
        for value in ["http://cdn.example.invalid/video","https://"+"user:password@cdn.example.invalid/video"]{
            CloudStub.handler={_ in(200,try JSONSerialization.data(withJSONObject:["url":value]))}
            do{_ = try await api().download("fixture-token",drive:"drive",file:"file");XCTFail("unsafe URL accepted")}catch CloudError.invalidResponse{}catch{XCTFail("unexpected error")}
        }
    }
    func testUnauthorizedIsTyped()async throws{
        CloudStub.handler={_ in(401,Data())}
        do{_ = try await api().drive("fixture-token");XCTFail("401 accepted")}catch CloudError.unauthorized{}catch{XCTFail("unexpected error")}
    }
}
