import org.apache.commons.collections4.MapUtils;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * @author 金丹
 * @since 2018/3/26.
 */
public class HttpClientUtil implements HttpClientWrapper {
    /**
     * 线程安全的 httpClient
     */
    private HttpClient client = new HttpClient(new MultiThreadedHttpConnectionManager());

    /**
     * 线程安全的 MAP 把cookie 信息设置为全局 类似用浏览器 在每个请求之间保持cookie的传递
     */
    private Map<String, String> cookies = new HashMap<String, String>();
    /**
     * 缓存协议头信息
     */
    List<Header> headers = Collections.synchronizedList(new ArrayList<Header>());

    /**
     * 空构造的是不带认证的 HttpClient操作
     */
    public HttpClientUtil() {
        super();
        setDefaultTimeout();
    }

    /**
     * 构造发送https请求的 HttpClient操作
     *
     * @param myhttps
     */
    public HttpClientUtil(Protocol myhttps) {
        super();
        Protocol.registerProtocol("https", myhttps);
        setDefaultTimeout();
    }

    /**
     * 设置代理模式
     *
     * @param proxyHost
     * @param proxyPort
     */
    public HttpClientUtil(final String proxyHost, final int proxyPort, final String usernamePassword) {
        client.getHostConfiguration().setProxy(proxyHost, proxyPort);
        client.getState().setProxyCredentials(new AuthScope(proxyHost, proxyPort),
                new UsernamePasswordCredentials(usernamePassword));
        setDefaultTimeout();
    }

    public HttpClientUtil(final String proxyHost, final int proxyPort, final String usernamePassword, int connectTimeout, int readTimeout) {
        client.getHostConfiguration().setProxy(proxyHost, proxyPort);
        client.getState().setProxyCredentials(new AuthScope(proxyHost, proxyPort),
                new UsernamePasswordCredentials(usernamePassword));
        client.getHttpConnectionManager().getParams().setConnectionTimeout(connectTimeout);
        client.getHttpConnectionManager().getParams().setSoTimeout(readTimeout);
    }

    /**
     * 带username和password 参数的 需要认证的 不论是basic 还是DIGEST （摘要） 认证都是一样
     *
     * @param userName
     * @param passWord
     */
    public HttpClientUtil(String userName, String passWord) {
        client.getState().setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, passWord));
        setDefaultTimeout();
    }

    public HttpClient getHttpClient() {
        return this.client;
    }

    public void addHttpHeader(Map<String, String> headers1) {
        headers = getHeaders();
        Set<String> names = headers1.keySet();
        for (String name : names) {
            for (Header header : headers) {
                if (header.getName().equals(name)) {
                    header.setValue(headers1.get(name));
                }
            }
            headers.add(new Header(name, headers1.get(name)));
        }
    }

    public void clearCookie() {
        client.getState().clearCookies();
    }

    public void addCookies(Cookie[] cookies1) {
        client.getState().addCookies(cookies1);
        Cookie[] cookies = client.getState().getCookies();
        this.setCookies(cookies);
    }

    public void addCookie(Cookie cookie) {
        client.getState().addCookie(cookie);
        Cookie[] cookies = client.getState().getCookies();
        this.setCookies(cookies);
    }

    public String doRequest(MethodType method, String url, Map<String, String> params, String charset)
            throws HttpException, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        doRequest(new HttpResponseCallBack() {
            public void processResponse(InputStream in) throws IOException {
                int b = -1;
                while ((b = in.read()) != -1) {
                    out.write(b);
                }
                out.flush();
                out.close();
                in.close();
            }
        }, method, url, params, charset);
        return out.toString(charset);
    }

    public String doRequest(MethodType method, String url, String charset)
            throws HttpException, IOException {
        return this.doRequest(method, url, null, charset);
    }

    /**
     * 处理返回流的请求 主要是针对下载的情况 利用的回调的形式在资源关闭之前 让用户操作流
     */
    public void doRequest(HttpResponseCallBack callback, MethodType method, String url, Map<String, String> params, String charset) throws HttpException, IOException {
        HttpMethod httpMethod = null;
        InputStream is = null;
        try {
            switch (method) {
                // 处理get请求
                case GET:
                    httpMethod = this.doGet(url, params, charset);
                    break;
                // 处理post请求
                case POST:
                    httpMethod = this.doPost(url, params, charset);
                    break;
                // 处理option请求
                case OPTION:
                    httpMethod = this.doOption(url, params, charset);
                    break;
                // 处理put请求
                case PUT:
                    httpMethod = this.doPut(url, params, charset);
                    break;
                case TRACE:
                    httpMethod = this.doTrace(url, params, charset);
                    break;
                case DELETE:
                    httpMethod = this.doDelete(url, params, charset);
                    break;
                default:
            }
            is = httpMethod.getResponseBodyAsStream();
            callback.processResponse(is);
        } catch (HttpException e) {
            if (httpMethod != null) {
                httpMethod.abort();
            }
            throw e;
        } catch (IOException e) {
            if (httpMethod != null) {
                httpMethod.abort();
            }
            throw e;
        } finally {
            //定期关闭空闲连接
            this.client.getHttpConnectionManager().closeIdleConnections(0);
            if (httpMethod != null) {
                this.closeConnection(httpMethod);
            }
            if (is != null) {
                is.close();
            }
        }
    }

    public void doRequest(HttpResponseCallBack callback, MethodType method,
                          String url, String charset) throws HttpException, IOException {
        this.doRequest(callback, method, url, null, charset);
    }

    /**
     * 预先设置好 http头信息
     *
     * @return list
     */
    private List<Header> getHeaders() {
        headers.add(new Header("Accept-Language", "zh-CN"));
        headers.add(new Header("Accept-Encoding", " gzip, deflate"));
        headers.add(new Header("Accept", "image/gif, image/x-xbitmap, image/jpeg, image/pjpeg, application/x-shockwave-flash,"
                + " application/vnd.ms-excel, application/vnd.ms-powerpoint, application/msword, */*"));
        headers.add(new Header("User-Agent",
                " Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1; InfoPath.2)"));
        headers.add(new Header("Connection", " Keep-Alive"));
        return headers;
    }

    /**
     * 保证每次的cookie信息都保持最新
     *
     * @param cookies1
     */
    private void setCookies(Cookie[] cookies1) {
        for (Cookie cookie : cookies1) {
            this.cookies.put(cookie.getName(), cookie.getValue());
        }
    }

    /**
     * 关闭请求过程中的网路连接
     *
     * @param method
     */
    private void closeConnection(HttpMethod method) {
        method.releaseConnection();
    }

    /**
     * post 请求时 把传进来的参数 进行封装
     *
     * @param params
     * @return NameValuePair[]
     */
    private NameValuePair[] postParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return new NameValuePair[0];
        }
        Set<String> paramNames = params.keySet();
        int i = 0;
        NameValuePair[] nameValuePairs = new NameValuePair[paramNames.size()];
        for (String paramName : paramNames) {
            NameValuePair nameValuePair = new NameValuePair(paramName, params.get(paramName));
            nameValuePairs[i] = nameValuePair;
            i++;
        }
        return nameValuePairs;
    }

    /**
     * 把map对象的的 cookie 信息变成 http 协议头里符合cookie的字符串
     *
     * @param cookies
     * @return String
     */
    private String cookieStr(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        String cookieStr = "";
        int i = 0;
        Set<String> cookieNames = cookies.keySet();
        for (String cookie : cookieNames) {
            i++;
            if (i == 1) {
                cookieStr = cookieStr + cookie + "=" + cookies.get(cookie);
            } else {
                cookieStr = cookieStr + ";" + cookie + "="
                        + cookies.get(cookie);
            }
        }
        return cookieStr;
    }

    /**
     * 处理get 请求
     *
     * @param url
     * @param params
     * @param charset
     * @return HttpMethod 主要是考虑到结果集有多种
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doGet(String url, Map<String, String> params, String charset) throws HttpException, IOException {
        String newUrl = createNewUrl(url, params);
        GetMethod get = new GetMethod(newUrl);
        //设置cookie信息
        get.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        get.setRequestHeader("Cookie", this.cookieStr(cookies));
        client.getParams().setContentCharset(charset);
        // 设置协议头
        client.getHostConfiguration().getParams().setParameter("http.default-headers", headers);
        // 发送get请求 异常直接抛出
        executeHttpMethod(get);
        return get;
    }

    /**
     * post请求, 由于post和put请求,HttpClient不支持自动重定向 所以我们的手动的重定向页面
     *
     * @param url
     * @param params
     * @param charset
     * @return HttpMethod
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doPost(String url, Map<String, String> params, String charset) throws HttpException, IOException {
        PostMethod post = new PostMethod(url);
        client.getParams().setContentCharset(charset);
        client.getHostConfiguration().getParams().setParameter("http.default-headers", headers);
        //设置post请求的的参数
        if (MapUtils.isNotEmpty(params)) {
            String json = params.get("json");
            if (StringUtils.isNotBlank(json) && json.equalsIgnoreCase("true")) {
                String contentType = "text/xml";
                if (params.get("contentType") != null) {
                    contentType = params.get("contentType");
                }
                RequestEntity requestEntity = new StringRequestEntity(params.get("param"), contentType, charset);
                post.setRequestEntity(requestEntity);
            } else {
                post.setRequestBody(this.postParams(params));
            }
        }
        //设置cookie信息
        post.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        post.setRequestHeader("Cookie", this.cookieStr(cookies));
        int status = executeHttpMethod(post);
        if (isRedirected(status)) {
            //获取返回过来的 URL信息
            Header locationHeader = post.getResponseHeader("location");
            //由于 HttpClient的post|put请求不支持 自动重定向 所以要手动重定向,而get等其他的 都是自动重定向
            String newUrl = "";
            if (locationHeader != null) {
                // 从协议头里获取需要重定向的url
                newUrl = locationHeader.getValue();
            } else {
                // 如果没有则返回默认的页面
                newUrl = "/";
            }
            // 构造get请求 并传入newurl
            GetMethod get = new GetMethod(newUrl);
            // 发送get 请求 手动重定向
            get.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
            get.setRequestHeader("Cookie", this.cookieStr(cookies));
            executeHttpMethod(get);
            return get;
        } else {
            return post;
        }
    }

    /**
     * 处理method请求 处理完之后把cookie信息都更新一遍 保持在各个请求之间和服务器的cookie信息一致
     *
     * @param httpMethod
     * @return status
     * @throws HttpException
     * @throws IOException
     */
    private int executeHttpMethod(HttpMethod httpMethod) throws HttpException, IOException {
        int status = this.client.executeMethod(httpMethod);
        this.setCookies(client.getState().getCookies());
        return status;
    }

    /**
     * 处理option 请求
     *
     * @param url
     * @param params
     * @param charset
     * @return
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doOption(String url, Map<String, String> params, String charset) {
        return null;
    }

    /**
     * 处理delete 请求
     *
     * @param url
     * @param params
     * @param charset
     * @return
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doDelete(String url, Map<String, String> params, String charset) {
        return null;
    }

    /**
     * 处理put 请求
     *
     * @param url
     * @param params
     * @param charset
     * @return
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doPut(String url, Map<String, String> params, String charset) {
        return null;
    }

    /**
     * 处理是否重定向
     *
     * @param status
     * @return
     */
    private boolean isRedirected(int status) {
        return status == HttpStatus.SC_MOVED_TEMPORARILY //302
                || status == HttpStatus.SC_MOVED_PERMANENTLY //301
                || status == HttpStatus.SC_TEMPORARY_REDIRECT //307
                || status == HttpStatus.SC_USE_PROXY //305
                || status == HttpStatus.SC_NOT_MODIFIED //304
                || status == HttpStatus.SC_SEE_OTHER; //303
    }

    /**
     * 处理trace 请求
     *
     * @param url
     * @param params
     * @param charset
     * @return
     * @throws HttpException
     * @throws IOException
     */
    private HttpMethod doTrace(String url, Map<String, String> params, String charset) {
        return null;
    }

    /**
     * 设置超时时间
     */
    private void setDefaultTimeout() {
        HttpConnectionManagerParams managerParams = client.getHttpConnectionManager().getParams();
        managerParams.setConnectionTimeout(DEFAULT_CONNECT_TIMEOUT);
        managerParams.setSoTimeout(DEFAULT_READ_TIMEOUT);
    }

    /***
     * get 请求时 如果带有参数 则用这方法 生成带参数的url
     * @param url
     * @param params
     * @return
     */
    private String createNewUrl(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        Set<String> names = params.keySet();
        int i = 0;
        for (String name : names) {
            i++;
            String value = params.get(name);
            if (i == 1) {
                url += "?" + name + "=" + value;
            } else {
                url += "&" + name + "=" + value;
            }
        }
        return url;
    }


}
