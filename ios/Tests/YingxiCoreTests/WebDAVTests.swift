import XCTest
@testable import YingxiCore
final class WebDAVTests: XCTestCase {
    func testHTTPSAndCredentialBoundary() throws {
        XCTAssertThrowsError(try DAVSource(name:"",address:"http://example.org/dav/",username:""))
        let s=try DAVSource(name:"NAS",address:"https://example.org/dav/",username:"reader")
        XCTAssertFalse(s.contains(URL(string:"https://other.example/dav/a.mp4")!))
        XCTAssertFalse(s.contains(URL(string:"https://example.org/dav-other/a.mp4")!))
        XCTAssertThrowsError(try s.request(url:URL(string:"https://other.example/dav/a.mp4")!,password:""))
    }
    func testRangeMetadataRejectsOverflowAndWrongTotals() throws {
        XCTAssertEqual(try ByteRange.parse("bytes 10-19/50"),ByteRange(start:10,end:19,total:50))
        for value in ["bytes 20-10/50","bytes 0-50/50","bytes 0-1/*","bytes 0-99999999999999999999999/50"] {XCTAssertThrowsError(try ByteRange.parse(value))}
    }
    func testListingIsSortedAndExcludesForeignFiles() throws {
        let source=try DAVSource(name:"",address:"https://example.org/dav/",username:"")
        let xml=#"<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/Episode10.mp4</d:href><d:propstat><d:prop><d:getcontentlength>120</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/Episode2.mp4</d:href><d:propstat><d:prop><d:getcontentlength>80</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>https://other.example/secret.mp4</d:href></d:response><d:response><d:href>/dav/Season/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"#
        let entries=try DAVListing(source:source,directory:source.baseURL).parse(Data(xml.utf8))
        XCTAssertEqual(entries.map(\.name),["Season","Episode2.mp4","Episode10.mp4"]);XCTAssertTrue(entries[0].directory);XCTAssertEqual(entries[2].size,120)
    }
    func testFailedPropsAndBrokenXMLAreRejected() throws {
        let s=try DAVSource(name:"",address:"https://example.org/dav/",username:"")
        XCTAssertThrowsError(try DAVListing(source:s,directory:s.baseURL).parse(Data("<broken".utf8)))
        XCTAssertThrowsError(try DAVListing(source:s,directory:s.baseURL).parse(Data("<html><body>Login</body></html>".utf8)))
        let xml=#"<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/no.mp4</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"#
        XCTAssertEqual(try DAVListing(source:s,directory:s.baseURL).parse(Data(xml.utf8)),[])
    }    func testMixedPropstatsKeepOnlySuccessfulProperties() throws {
        let source=try DAVSource(name:"",address:"https://example.org/dav/",username:"")
        let xml=#"<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/movie.mp4</d:href><d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:displayname>unreadable</d:displayname></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"#
        let entries=try DAVListing(source:source,directory:source.baseURL).parse(Data(xml.utf8));XCTAssertEqual(entries.first?.name,"movie.mp4");XCTAssertEqual(entries.first?.size,42)
    }

}
