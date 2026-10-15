FROM python:3.11-slim
WORKDIR /app
# pip 镜像可通过构建参数切换（服务器上清华源更快）
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ENV PIP_INDEX_URL=${PIP_INDEX_URL} PIP_DEFAULT_TIMEOUT=60 PYTHONUNBUFFERED=1
# 是否安装本地模型依赖（torch + sentence-transformers，镜像增加约 1.5GB）。
# 默认 false：使用阿里云百炼云端向量/重排，适合 4GB 内存服务器；需要本地 bge-m3 时构建参数设为 true
ARG WITH_LOCAL_MODELS=false
COPY requirements.txt requirements-local.txt ./
# 1. 安装基础依赖；2. 按需安装 CPU 版 torch 和本地模型依赖；
# 3. rapidocr 依赖的 opencv-python 需要系统图形库 libGL，slim 镜像没有，换成功能相同的无界面版 opencv-python-headless
# BuildKit 缓存挂载：pip 下载缓存在多次构建之间复用（requirements 变化时只下载新增包）
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.txt \
    && if [ "$WITH_LOCAL_MODELS" = "true" ]; then \
         pip install torch --index-url https://download.pytorch.org/whl/cpu \
         && pip install -r requirements-local.txt; \
       fi \
    && pip uninstall -y opencv-python \
    && pip install opencv-python-headless==4.10.0.84
COPY app ./app
COPY eval ./eval
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]