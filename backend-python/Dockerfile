FROM python:3.11-slim
WORKDIR /app
ENV PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ PYTHONUNBUFFERED=1
COPY requirements.txt .
# 1. 先装 CPU 版 torch（体积小，不含 CUDA）；2. 再装其余依赖；
# 3. rapidocr 依赖的 opencv-python 需要系统图形库 libGL，slim 镜像没有，换成功能相同的无界面版 opencv-python-headless
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt \
    && pip uninstall -y opencv-python \
    && pip install --no-cache-dir opencv-python-headless==4.10.0.84
COPY app ./app
COPY eval ./eval
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
