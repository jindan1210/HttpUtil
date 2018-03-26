import java.io.IOException;
import java.io.InputStream;

/**
 * @author 金丹
 * @since 2018/3/26.
 */
public interface HttpResponseCallBack {
    /**
     *
     * @param responseBody
     * @throws IOException
     */
    void processResponse(InputStream responseBody) throws IOException;
}
