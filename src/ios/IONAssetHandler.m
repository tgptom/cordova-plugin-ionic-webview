#import "IONAssetHandler.h"
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#if __IPHONE_OS_VERSION_MIN_REQUIRED < 140000
#import <MobileCoreServices/MobileCoreServices.h>
#endif
#import "CDVWKWebViewEngine.h"

@implementation IONAssetHandler

-(void)setAssetPath:(NSString *)assetPath {
    self.basePath = assetPath;
}

- (instancetype)initWithBasePath:(NSString *)basePath andScheme:(NSString *)scheme {
    self = [super init];
    if (self) {
        _basePath = basePath;
        _scheme = scheme;
    }
    return self;
}

- (void)webView:(WKWebView *)webView startURLSchemeTask:(id <WKURLSchemeTask>)urlSchemeTask
{
    NSString * startPath = @"";
    NSURL * url = urlSchemeTask.request.URL;
    NSString * stringToLoad = url.path;
    NSString * scheme = url.scheme;

    if ([scheme isEqualToString:self.scheme]) {
        if ([stringToLoad hasPrefix:@"/_app_file_"]) {
            startPath = [stringToLoad stringByReplacingOccurrencesOfString:@"/_app_file_" withString:@""];
        } else {
            startPath = self.basePath ? self.basePath : @"";
            if ([stringToLoad isEqualToString:@""] || [url.pathExtension isEqualToString:@""]) {
                startPath = [startPath stringByAppendingString:@"/index.html"];
            } else {
                startPath = [startPath stringByAppendingString:stringToLoad];
            }
        }
    }
    NSError * fileError = nil;
    NSData * data = nil;
    if ([self isMediaExtension:url.pathExtension]) {
        data = [NSData dataWithContentsOfFile:startPath options:NSDataReadingMappedIfSafe error:&fileError];
    }
    if (!data || fileError) {
        data =  [[NSData alloc] initWithContentsOfFile:startPath];
    }
    NSInteger statusCode = 200;
    if (!data) {
        statusCode = 404;
    }
    NSURL * localUrl = [NSURL URLWithString:url.absoluteString];
    NSString * mimeType = [self getMimeType:url.pathExtension];
    id response = nil;
    if (data && [self isMediaExtension:url.pathExtension]) {
        response = [[NSURLResponse alloc] initWithURL:localUrl MIMEType:mimeType expectedContentLength:data.length textEncodingName:nil];
    } else {
        NSDictionary * headers = @{ @"Content-Type" : mimeType, @"Cache-Control": @"no-cache"};
        response = [[NSHTTPURLResponse alloc] initWithURL:localUrl statusCode:statusCode HTTPVersion:nil headerFields:headers];
    }
    
    [urlSchemeTask didReceiveResponse:response];
    [urlSchemeTask didReceiveData:data];
    [urlSchemeTask didFinish];

}

- (void)webView:(nonnull WKWebView *)webView stopURLSchemeTask:(nonnull id<WKURLSchemeTask>)urlSchemeTask
{
    NSLog(@"stop");
}

-(NSString *) getMimeType:(NSString *)fileExtension {
    if (fileExtension && ![fileExtension isEqualToString:@""]) {
#if __IPHONE_OS_VERSION_MIN_REQUIRED < 140000
        if (@available(iOS 14.0, *)) {
            UTType *utType = [UTType typeWithFilenameExtension:fileExtension];
            NSString *mimeType = utType.preferredMIMEType;
            return mimeType ? mimeType : @"application/octet-stream";
        } else {
            // Fallback for pre-iOS 14 runtimes when building with a deployment target below iOS 14.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            NSString *UTI = (__bridge_transfer NSString *)UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, (__bridge CFStringRef)fileExtension, NULL);
            NSString *contentType = (__bridge_transfer NSString *)UTTypeCopyPreferredTagWithClass((__bridge CFStringRef)UTI, kUTTagClassMIMEType);
#pragma clang diagnostic pop
            return contentType ? contentType : @"application/octet-stream";
        }
#else
        UTType *utType = [UTType typeWithFilenameExtension:fileExtension];
        NSString *mimeType = utType.preferredMIMEType;
        return mimeType ? mimeType : @"application/octet-stream";
#endif
    } else {
        return @"text/html";
    }
}

-(BOOL) isMediaExtension:(NSString *) pathExtension {
    NSArray * mediaExtensions = @[@"m4v", @"mov", @"mp4",
                           @"aac", @"ac3", @"aiff", @"au", @"flac", @"m4a", @"mp3", @"wav"];
    if ([mediaExtensions containsObject:pathExtension.lowercaseString]) {
        return YES;
    }
    return NO;
}


@end
